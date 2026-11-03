package net.ptcrys.fpsmatch.editor.core.viewportprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.FrameMatrix;
import net.ptcrys.fpsmatch.editor.common.client.workbench.WorkbenchScreen;
import net.ptcrys.fpsmatch.editor.common.client.workbench.StudioClientResources;
import net.ptcrys.fpsmatch.editor.common.client.workbench.WorkbenchWorldViewport;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.input.SingleLineText;
import org.lwjgl.glfw.GLFW;

/**
 * Explicit development source set only, merged into the existing fpsmatch mod.
 * The packaged CLI's test-only module starts each run. No files or alternate control transport
 * trigger input. The probe sends Screen events, then observes ordinary ticks and rendered frames.
 */
@Mod.EventBusSubscriber(modid = "fpsmatch", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ViewportCameraProbe {
    private static final String MODULE_ID = "fpsmatch:viewport_probe";
    private static final int SETTLE_TICKS = 3;
    private static final double EPSILON = 0.00001;
    private static boolean registrationRequested;
    /** All session state except its cancellation request/future belongs to the client thread. */
    private static Session active;
    private static TextSession activeText;

    private ViewportCameraProbe() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        GameplayInputProbe.tick(enabled());
        UiGestureProbe.tick(enabled());
        if (!enabled()) return;
        if (!registrationRequested) {
            CameraModule module = new CameraModule();
            ViewportAgentBridge.registerAsync(MODULE_ID, module.schema(), module::execute);
            registrationRequested = true;
        }
        if (active != null) active.tick();
        if (activeText != null) activeText.tick();
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (enabled() && event.phase == TickEvent.Phase.END && active != null) active.rendered();
        if (enabled() && event.phase == TickEvent.Phase.END && activeText != null) activeText.rendered();
    }

    private static boolean enabled() {
        return Boolean.getBoolean("editorViewportProbe.enabled") && Boolean.getBoolean("mcagent.testMode");
    }

    static boolean anyInputProbeActive() {
        return active != null || activeText != null || GameplayInputProbe.isActive() || UiGestureProbe.isActive();
    }

    private record FaultTarget(MinecraftServer server, UUID actor, ExtensionRegistry registry) {}

    /** Request native activation only; never manufacture Minecraft or editor focus state. */
    private static JsonObject focusUi(ViewportAgentBridge.ActiveRun context, JsonObject args,
                                      Minecraft minecraft) throws Exception {
        require(args.keySet().equals(Set.of("action")), "ui_focus_accepts_action_only");
        context.checkActive();
        JsonObject report = new JsonObject();
        report.addProperty("action", "ui_focus");
        report.addProperty("precondition_only", true);
        report.add("before", minecraft.submit(() -> {
            JsonObject before = focusState(minecraft);
            try {
                context.runClientInput(() -> {
                    require(enabled() && !anyInputProbeActive(), "ui_focus_development_run_required");
                    require(!minecraft.getWindow().isFullscreen(), "ui_focus_requires_windowed_client");
                    long window = minecraft.getWindow().getWindow();
                    GLFW.glfwRestoreWindow(window);
                    GLFW.glfwFocusWindow(window);
                });
            } catch (Exception inactive) { throw new IllegalStateException("ui_focus_run_inactive", inactive); }
            return before;
        }).get(10, TimeUnit.SECONDS));
        JsonArray samples = new JsonArray();
        report.add("samples", samples);
        int consecutive = 0;
        for (int attempt = 0; attempt < 30; attempt++) {
            Thread.sleep(100);
            context.checkActive();
            JsonObject sample = minecraft.submit(() -> {
                try { context.checkClientActive(); }
                catch (Exception inactive) { throw new IllegalStateException("ui_focus_run_inactive", inactive); }
                return focusState(minecraft);
            }).get(10, TimeUnit.SECONDS);
            samples.add(sample);
            boolean active = sample.get("actual_window_active").getAsBoolean()
                    && sample.get("native_window_focused").getAsBoolean()
                    && (!sample.has("input_bridge_active") || sample.get("input_bridge_active").getAsBoolean());
            consecutive = active ? consecutive + 1 : 0;
            if (consecutive >= 3) break;
        }
        report.addProperty("ok", consecutive >= 3);
        report.addProperty("reason", consecutive >= 3 ? "native_activation_observed" : "native_activation_not_observed");
        return report;
    }

    private static JsonObject focusState(Minecraft minecraft) {
        JsonObject sample = new JsonObject();
        long window = minecraft.getWindow().getWindow();
        sample.addProperty("actual_window_active", minecraft.isWindowActive());
        sample.addProperty("native_window_focused", GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE);
        sample.addProperty("native_iconified", GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE);
        sample.addProperty("screen", minecraft.screen == null ? "none" : minecraft.screen.getClass().getName());
        if (minecraft.screen instanceof WorkbenchScreen screen) {
            Object surface = readField(screen, "ui");
            Object bridge = readField(surface, "inputBridge");
            ControlTree tree = (ControlTree)readField(surface, "tree");
            sample.addProperty("input_bridge_active", (Boolean)readField(bridge, "active"));
            sample.addProperty("keyboard_focus_visible", (Boolean)readField(surface, "keyboardFocus"));
            sample.addProperty("focused_id", tree.focused().map(ControlTree.Node::id).orElse(null));
            Object pending = readField(bridge, "pending");
            sample.addProperty("pending_focus_id", pending == null ? null : (String)readField(pending, "id"));
            sample.addProperty("pending_expected_focus_id", pending == null ? null : (String)readField(pending, "expectedFocus"));
        }
        return sample;
    }

    /** Test precondition only: native window events, no world, editor state or synthetic ticks. */
    private static JsonObject resizeUi(ViewportAgentBridge.ActiveRun context, JsonObject args,
                                       Minecraft minecraft) throws Exception {
        require(args.keySet().equals(Set.of("action", "width", "height")), "ui_resize_requires_size_only");
        int width = args.get("width").getAsBigDecimal().intValueExact();
        int height = args.get("height").getAsBigDecimal().intValueExact();
        require(Set.of("1280x720", "854x480", "1902x991", "3408x2166", "1600x900")
                .contains(width + "x" + height), "ui_resize_size_not_in_acceptance_matrix");
        context.checkActive();
        minecraft.submit(() -> {
            require(enabled() && !anyInputProbeActive(), "ui_resize_development_run_required");
            require(!minecraft.getWindow().isFullscreen(), "ui_resize_requires_windowed_client");
            try {
                context.runClientInput(() -> {
                    long window = minecraft.getWindow().getWindow();
                    GLFW.glfwRestoreWindow(window);
                    GLFW.glfwSetWindowSize(window, width, height);
                });
            } catch (Exception inactive) { throw new IllegalStateException("ui_resize_run_inactive", inactive); }
            return true;
        }).get(10, TimeUnit.SECONDS);
        // Let GLFW dispatch its real framebuffer callback. Each sample is read on the client;
        // cancellation is checked there too, so no queued sample mutates a later session.
        for (int attempt = 0; attempt < 30; attempt++) {
            Thread.sleep(100);
            context.checkActive();
            JsonObject sample = minecraft.submit(() -> {
                try { context.checkClientActive(); }
                catch (Exception inactive) { throw new IllegalStateException("ui_resize_run_inactive", inactive); }
                var window = minecraft.getWindow();
                int[] actualWidth = new int[1], actualHeight = new int[1];
                GLFW.glfwGetWindowSize(window.getWindow(), actualWidth, actualHeight);
                JsonObject result = new JsonObject();
                result.addProperty("action", "ui_resize");
                result.addProperty("requested_width", width);
                result.addProperty("requested_height", height);
                result.addProperty("native_width", actualWidth[0]);
                result.addProperty("native_height", actualHeight[0]);
                result.addProperty("framebuffer_width", window.getWidth());
                result.addProperty("framebuffer_height", window.getHeight());
                result.addProperty("gui_width", window.getGuiScaledWidth());
                result.addProperty("gui_height", window.getGuiScaledHeight());
                result.addProperty("screen", minecraft.screen == null ? "none" : minecraft.screen.getClass().getName());
                result.addProperty("ok", actualWidth[0] == width && actualHeight[0] == height
                        && window.getWidth() == width && window.getHeight() == height);
                result.addProperty("precondition_only", true);
                return result;
            }).get(10, TimeUnit.SECONDS);
            if (sample.get("ok").getAsBoolean()) return sample;
        }
        throw new IllegalStateException("ui_resize_native_callback_did_not_reach_requested_size");
    }

    private static final class CameraModule {
        private JsonObject schema() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            JsonObject properties = new JsonObject();
            properties.add("action", choices("move", "look", "stationary", "cancel_menu", "cancel_text", "resize", "ui_resize", "cancel_focus", "text_snapshot", "text_undo", "text_redo", "native_persistence", "native_runtime_snapshot", "native_runtime_test_faults", "native_start_reset_failure", "native_runtime_authority_faults", "native_permission_loss", "native_queued_server_shutdown", "native_cs_start_faults", "game_escape", "game_editor", "game_input"));
            properties.add("key", choices("W", "A", "S", "D", "B", "E", "use"));
            properties.getAsJsonObject("action").getAsJsonArray("enum").add("ui_gesture");
            properties.getAsJsonObject("action").getAsJsonArray("enum").add("native_runtime_end");
            properties.getAsJsonObject("action").getAsJsonArray("enum").add("single_round_timeout");
            properties.getAsJsonObject("action").getAsJsonArray("enum").add("native_physical_world");
            properties.getAsJsonObject("action").getAsJsonArray("enum").add("native_cs_round_reset");
            properties.getAsJsonObject("action").getAsJsonArray("enum").add("native_cs_round_observe");
            for (String action : List.of("native_cs_round_observe_start", "native_cs_round_observe_poll",
                    "native_cs_round_observe_cancel", "native_cs_round_observe_cancel_request"))
                properties.getAsJsonObject("action").getAsJsonArray("enum").add(action);
            for (String name : List.of("request_id", "observation_id")) {
                JsonObject identity = new JsonObject(); identity.addProperty("type", "string");
                identity.addProperty("minLength", 36); identity.addProperty("maxLength", 36);
                properties.add(name, identity);
            }
            properties.add("mode", choices("natural_stop", "natural_tick", "partial_stop", "partial_tick", "late_partial_stop", "late_partial_tick", "caught_nested_stop"));
            properties.add("game_type", choices("cs", "csdm"));
            JsonObject mapName = new JsonObject(); mapName.addProperty("type", "string"); properties.add("map_name", mapName);
            for (String action : List.of("ui_focus", "ui_focus_snapshot", "ui_tab", "ui_backtab", "ui_enter", "ui_reload"))
                properties.getAsJsonObject("action").getAsJsonArray("enum").add(action);
            properties.add("finish", choices("release", "escape", "resize", "focus"));
            for (String name : List.of("start_x", "start_y", "end_x", "end_y")) {
                JsonObject coordinate = new JsonObject(); coordinate.addProperty("type", "number");
                coordinate.addProperty("minimum", 0); properties.add(name, coordinate);
            }
            properties.add("order", choices("key_first", "button_first"));
            properties.add("release", choices("key", "button"));
            JsonObject ticks = new JsonObject();
            ticks.addProperty("type", "integer"); ticks.addProperty("minimum", 1); ticks.addProperty("maximum", 240);
            properties.add("ticks", ticks);
            for (String name : List.of("width", "height")) {
                JsonObject size = new JsonObject(); size.addProperty("type", "integer");
                properties.add(name, size);
            }
            for (String name : List.of("dx", "dy")) {
                JsonObject number = new JsonObject(); number.addProperty("type", "number");
                number.addProperty("minimum", -100); number.addProperty("maximum", 100);
                properties.add(name, number);
            }
            schema.add("properties", properties);
            schema.addProperty("additionalProperties", false);
            return schema;
        }

        private JsonObject execute(ViewportAgentBridge.ActiveRun context, JsonObject args) throws Exception {
            if (!enabled()) throw new IllegalStateException("viewport_probe_explicit_development_flags_required");
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.isSameThread()) throw new IllegalStateException("viewport_probe_requires_module_worker_thread");
            String action = string(args, "action", "move");
            if (action.equals("ui_resize")) return resizeUi(context, args, minecraft);
            if (action.equals("ui_focus")) return focusUi(context, args, minecraft);
            if (action.equals("game_input")) return GameplayInputProbe.execute(context, args);
            if (action.equals("ui_gesture")) return UiGestureProbe.execute(context, args);
            if (action.equals("game_escape") || action.equals("game_editor")) {
                require(args.keySet().equals(Set.of("action")), "game_escape_accepts_action_only");
                context.checkActive();
                JsonObject eventResult=minecraft.submit(()->{
                    require(!anyInputProbeActive(), "another_viewport_probe_is_active");
                    require(minecraft.screen==null&&minecraft.player!=null,"gameplay_without_screen_required");
                    long window=minecraft.getWindow().getWindow();
                    try { minecraft.keyboardHandler.keyPress(window,GLFW.GLFW_KEY_ESCAPE,0,GLFW.GLFW_PRESS,0); }
                    finally { minecraft.keyboardHandler.keyPress(window,GLFW.GLFW_KEY_ESCAPE,0,GLFW.GLFW_RELEASE,0); }
                    JsonObject result=new JsonObject();
                    result.addProperty("action",action);
                    result.addProperty("screen",minecraft.screen==null?"none":minecraft.screen.getClass().getName());
                    result.addProperty("native_keyboard_handler",true);
                    require(minecraft.screen instanceof net.minecraft.client.gui.screens.PauseScreen,"native_pause_not_opened");
                    if(action.equals("game_editor")) {
                        // The agent dismisses PauseScreen while its controller lease is active.
                        // Click the real pause-menu button in this event, before the next tick;
                        // never open the workbench directly or rewrite the agent's state.
                        var pause=minecraft.screen;
                        var buttons=pause.children().stream()
                                .filter(net.minecraft.client.gui.components.Button.class::isInstance)
                                .map(net.minecraft.client.gui.components.Button.class::cast)
                                .filter(button->button.getMessage().getString().equals(
                                        net.minecraft.network.chat.Component.translatable("editor.workbench.open").getString()))
                                .toList();
                        require(buttons.size()==1,"unique_native_editor_button_required");
                        var button=buttons.get(0);
                        double x=button.getX()+button.getWidth()/2.0,y=button.getY()+button.getHeight()/2.0;
                        boolean handled=pause.mouseClicked(x,y,GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        pause.mouseReleased(x,y,GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        result.addProperty("pause_button_clicked",handled);
                        result.addProperty("pause_button_caption",button.getMessage().getString());
                        result.addProperty("stable_pause_visual_not_observed",true);
                        result.addProperty("screen_after_click",minecraft.screen==null?"none":minecraft.screen.getClass().getName());
                        require(handled,"pause_editor_button_did_not_handle_click");
                    }
                    return result;
                }).get(30,TimeUnit.SECONDS);
                if(action.equals("game_editor"))return minecraft.submit(()->{
                    // WorkbenchClient defers its open until after the button event.
                    require(minecraft.screen instanceof WorkbenchScreen,"pause_editor_button_did_not_open_workbench");
                    eventResult.addProperty("screen_after_queued_open",minecraft.screen.getClass().getName());
                    return eventResult;
                }).get(30,TimeUnit.SECONDS);
                return eventResult;
            }
            if (action.equals("native_runtime_end") || action.equals("native_physical_world") || action.equals("single_round_timeout")) {
                Set<String> expected = action.equals("native_physical_world") ? Set.of("action")
                        : action.equals("single_round_timeout") ? Set.of("action", "map_name")
                        : Set.of("action", "mode", "game_type", "map_name");
                require(args.keySet().equals(expected), "exact_runtime_regression_arguments_required");
                context.checkActive();
                FaultTarget target=minecraft.submit(()->{
                    var server=minecraft.getSingleplayerServer();
                    require(server!=null&&minecraft.player!=null, "isolated_server_actor_required");
                    FPSMatch mod=(FPSMatch)ModList.get().getModContainerById("fpsmatch").orElseThrow().getMod();
                    return new FaultTarget(server,minecraft.player.getUUID(),mod.editorRegistry());
                }).get(30,TimeUnit.SECONDS);
                if (action.equals("native_runtime_end")) return RuntimeNaturalEndProbe.execute(target.server(),target.actor(),target.registry(),args.get("game_type").getAsString(),args.get("map_name").getAsString(),args.get("mode").getAsString());
                if (action.equals("single_round_timeout")) return RuntimeNaturalEndProbe.executeSingleRoundTimeout(target.server(),target.actor(),target.registry(),args.get("map_name").getAsString());
                CompletableFuture<JsonObject> result=new CompletableFuture<>();
                target.server().execute(()->{try{
                    String root=target.server().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().toString().replace('\\','/');
                    require(root.contains("/Q82/ui-host-"), "isolated_world_required");
                    net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime.RuntimePhysicalWorldGameTests.verifyPhysicalWorld(target.server());
                    net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime.RuntimePhysicalWorldGameTests.registeredWorldCopyCannotInheritAuthority(target.server());
                    JsonObject report=new JsonObject();report.addProperty("status","PASS");report.addProperty("same_uuid_copy_refused",true);report.add("reset_observation",RuntimeNaturalEndProbe.observeResetContract(target.server()));result.complete(report);
                }catch(Throwable failure){result.completeExceptionally(failure);}});
                return result.get(30,TimeUnit.SECONDS);
            }
            if (action.equals("native_runtime_test_faults") || action.equals("native_runtime_authority_faults") || action.equals("native_cs_start_faults")
                    || action.equals("native_permission_loss") || action.equals("native_queued_server_shutdown") || action.equals("native_start_reset_failure")) {
                require(args.keySet().equals(Set.of("action")), "native_faults_accepts_action_only");
                context.checkActive();
                FaultTarget target=minecraft.submit(()->{
                    var server=minecraft.getSingleplayerServer();
                    if(server==null||minecraft.player==null)throw new IllegalStateException("isolated_server_actor_required");
                    FPSMatch mod=(FPSMatch)ModList.get().getModContainerById("fpsmatch").orElseThrow().getMod();
                    return new FaultTarget(server,minecraft.player.getUUID(),mod.editorRegistry());
                }).get(30,TimeUnit.SECONDS);
                if (action.equals("native_cs_start_faults"))
                    return NativeCsStartFaults.execute(target.server(),target.actor(),target.registry());
                if (action.equals("native_start_reset_failure"))
                    return NativeRuntimeTestFaults.executeInternalReset(target.server(),target.actor(),target.registry());
                if (action.equals("native_permission_loss") || action.equals("native_queued_server_shutdown"))
                    return NativeRuntimeAuthorityFaults.executeRemaining(target.server(),target.actor(),target.registry(),
                            action.equals("native_permission_loss") ? "current_permission_loss" : "queued_server_shutdown");
                return action.equals("native_runtime_authority_faults")
                        ? NativeRuntimeAuthorityFaults.execute(target.server(),target.actor(),target.registry())
                        : NativeRuntimeTestFaults.execute(target.server(),target.actor(),target.registry());
            }
            if (List.of("text_snapshot", "text_undo", "text_redo", "ui_focus_snapshot", "ui_tab", "ui_backtab", "ui_enter", "ui_reload").contains(action))
                return executeText(context, args, action, minecraft);
            if (action.equals("native_cs_round_reset")) {
                require(args.keySet().equals(Set.of("action")), "native_round_reset_accepts_action_only");
                context.checkActive();
                CompletableFuture<JsonObject> result = new CompletableFuture<>();
                minecraft.execute(() -> {
                    var server = minecraft.getSingleplayerServer();
                    if (server == null) { result.completeExceptionally(new IllegalStateException("isolated_server_required")); return; }
                    server.execute(() -> {
                        try { result.complete(NativeCsRoundResetChecks.execute(server)); }
                        catch (Throwable failure) { result.completeExceptionally(failure); }
                    });
                });
                return result.get(30, TimeUnit.SECONDS);
            }
            if (Set.of("native_cs_round_observe_start", "native_cs_round_observe_poll",
                    "native_cs_round_observe_cancel", "native_cs_round_observe_cancel_request").contains(action)) {
                boolean requestIdentity = action.equals("native_cs_round_observe_start")
                        || action.equals("native_cs_round_observe_cancel_request");
                String identityKey = requestIdentity ? "request_id" : "observation_id";
                require(args.keySet().equals(Set.of("action", identityKey)), "native_round_observer_requires_exact_identity");
                String identity = string(args, identityKey, "");
                require(java.util.UUID.fromString(identity).toString().equals(identity), "native_round_observer_requires_canonical_uuid");
                context.checkActive();
                boolean cancelling = action.equals("native_cs_round_observe_cancel")
                        || action.equals("native_cs_round_observe_cancel_request");
                var server = minecraft.submit(() -> {
                    var integrated = minecraft.getSingleplayerServer();
                    require(cancelling || integrated != null, "isolated_server_required");
                    return integrated;
                }).get(10, TimeUnit.SECONDS);
                return switch (action) {
                    case "native_cs_round_observe_start" -> NativeCsRoundObservation.begin(server, identity, () -> {
                        try { context.checkActive(); }
                        catch (Exception cancelled) { throw new IllegalStateException("round_observer_start_cancelled", cancelled); }
                    });
                    case "native_cs_round_observe_poll" -> NativeCsRoundObservation.poll(server, identity);
                    case "native_cs_round_observe_cancel" -> NativeCsRoundObservation.cancel(server, identity);
                    default -> NativeCsRoundObservation.cancelByRequest(server, identity);
                };
            }
            if (action.equals("native_cs_round_observe")) {
                require(args.keySet().equals(Set.of("action")), "native_round_observer_accepts_action_only");
                context.checkActive();
                var server = minecraft.submit(() -> {
                    var integrated = minecraft.getSingleplayerServer();
                    require(integrated != null && minecraft.player != null, "isolated_server_actor_required");
                    return integrated;
                }).get(10, TimeUnit.SECONDS);
                return NativeCsRoundObservation.execute(server, () -> {
                    try { context.checkActive(); }
                    catch (Exception cancelled) { throw new IllegalStateException("round_observer_cancelled", cancelled); }
                });
            }
            if (action.equals("native_runtime_snapshot")) {
                require(args.keySet().equals(Set.of("action")), "native_snapshot_accepts_action_only");
                context.checkActive();
                CompletableFuture<JsonObject> result = new CompletableFuture<>();
                minecraft.execute(() -> {
                    var server = minecraft.getSingleplayerServer();
                    if (server == null) { result.completeExceptionally(new IllegalStateException("isolated_server_required")); return; }
                    server.execute(() -> {
                        try { result.complete(NativeRuntimeSnapshot.capture(server)); }
                        catch (Throwable failure) { result.completeExceptionally(failure); }
                    });
                });
                return result.get(30, TimeUnit.SECONDS);
            }
            if (action.equals("native_persistence")) {
                require(args.keySet().equals(Set.of("action")), "native_probe_accepts_action_only");
                context.checkActive();
                CompletableFuture<JsonObject> result = new CompletableFuture<>();
                minecraft.execute(() -> {
                    try {
                        if (anyInputProbeActive()) throw new IllegalStateException("another_probe_is_active");
                        result.complete(NativePersistenceChecks.run());
                    } catch (Throwable failure) { result.completeExceptionally(failure); }
                });
                return result.get(60, TimeUnit.SECONDS);
            }
            Request request = Request.read(args);
            Session session = new Session(request);
            // Also checks the owning module run is still active before any client input is queued.
            context.checkActive();
            minecraft.execute(() -> {
                if (session.cancelReason != null) { session.fail(session.cancelReason); return; }
                if (anyInputProbeActive()) { session.fail("another_viewport_probe_is_active"); return; }
                active = session;
                session.begin();
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            try {
                while (true) {
                    try { return session.result.get(250, TimeUnit.MILLISECONDS); }
                    catch (TimeoutException waiting) {
                        // The context rejects calls after modules.cancel, so cancellation is
                        // propagated without a second control channel or a blocking client loop.
                        context.checkActive();
                        if (System.nanoTime() >= deadline) throw new IllegalStateException("viewport_probe_real_tick_or_render_timeout");
                    }
                }
            } finally {
                if (!session.result.isDone()) {
                    session.cancelReason = "module_cancelled_or_timed_out";
                    minecraft.execute(() -> session.fail(session.cancelReason));
                }
            }
        }

        private JsonObject executeText(ViewportAgentBridge.ActiveRun context, JsonObject args,
                                       String action, Minecraft minecraft) throws Exception {
            require(args.keySet().equals(Set.of("action")), "text_probe_accepts_action_only");
            TextSession session = new TextSession(action, context);
            context.checkActive();
            minecraft.execute(() -> {
                if (session.cancelReason != null) { session.finish(false, session.cancelReason); return; }
                if (anyInputProbeActive()) { session.finish(false, "another_viewport_probe_is_active"); return; }
                activeText = session;
                session.begin();
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(action.equals("ui_reload") ? 45 : 20);
            try {
                while (true) {
                    try { return session.result.get(250, TimeUnit.MILLISECONDS); }
                    catch (TimeoutException waiting) {
                        context.checkActive();
                        if (System.nanoTime() >= deadline) throw new IllegalStateException("text_probe_real_tick_or_render_timeout");
                    }
                }
            } finally {
                if (!session.result.isDone()) {
                    session.cancelReason = "module_cancelled_or_timed_out";
                    minecraft.execute(() -> session.finish(false, session.cancelReason));
                }
            }
        }
    }

    /** Observes retained text state around real Screen key events; no model or history writes. */
    private static final class TextSession {
        private final String action;
        private final ViewportAgentBridge.ActiveRun context;
        private final CompletableFuture<JsonObject> result = new CompletableFuture<>();
        private final JsonArray samples = new JsonArray();
        private final Minecraft minecraft = Minecraft.getInstance();
        private volatile String cancelReason;
        private WorkbenchScreen screen;
        private int realTicks;
        private boolean pressed, released;
        private CompletableFuture<Void> resourceReload;
        private long initialResourceGeneration,initialShaderGeneration;
        private int reloadSettledFrames;
        private final int key, modifiers;

        private TextSession(String action, ViewportAgentBridge.ActiveRun context) {
            this.action = action;
            this.context = context;
            key = switch (action) {
                case "text_undo" -> GLFW.GLFW_KEY_Z;
                case "text_redo" -> GLFW.GLFW_KEY_Y;
                case "ui_tab", "ui_backtab" -> GLFW.GLFW_KEY_TAB;
                case "ui_enter" -> GLFW.GLFW_KEY_ENTER;
                default -> -1;
            };
            modifiers = action.equals("ui_backtab") ? GLFW.GLFW_MOD_SHIFT
                    : action.equals("text_undo") || action.equals("text_redo") ? GLFW.GLFW_MOD_CONTROL : 0;
        }

        private void begin() {
            try {
                require(minecraft.isSameThread(), "text_probe_not_on_client_thread");
                require(minecraft.screen instanceof WorkbenchScreen, "exact_workbench_host_required");
                screen = (WorkbenchScreen)minecraft.screen;
                context.runClientInput(() -> {
                    require(enabled() && cancelReason == null, "key_probe_cancelled");
                    JsonObject before = snapshot("before");
                    samples.add(before);
                    if (action.startsWith("text_"))
                        require(before.get("text_field_focused").getAsBoolean(), "focused_text_field_required");
                    if(action.equals("ui_reload")){
                        require(before.get("actual_window_active").getAsBoolean() && before.get("native_window_focused").getAsBoolean(),"reload_probe_window_inactive");
                        require(minecraft.getOverlay()==null,"reload_probe_overlay_active");
                        initialResourceGeneration=StudioClientResources.generation();initialShaderGeneration=StudioClientResources.shaderGeneration();
                        resourceReload=minecraft.reloadResourcePacks();
                    }
                    if (key >= 0) {
                        require(before.get("actual_window_active").getAsBoolean()
                                && before.get("native_window_focused").getAsBoolean()
                                && before.get("input_bridge_active").getAsBoolean(), "key_probe_window_inactive");
                        // Read actual physical modifier state before dispatch. Never alter it.
                        try { pressed = screen.keyPressed(key, 0, modifiers); }
                        finally { released = screen.keyReleased(key, 0, modifiers); }
                    }
                    samples.add(snapshot("immediate"));
                });
            } catch (Exception failure) { finish(false, failure.toString()); }
        }

        private void tick() {
            if (result.isDone()) return;
            if (cancelReason != null) { finish(false, cancelReason); return; }
            if (minecraft.screen != screen) { finish(false, "text_probe_screen_changed"); return; }
            realTicks++;
        }

        private void rendered() {
            if (result.isDone() || realTicks < SETTLE_TICKS) return;
            try {
                require(minecraft.screen == screen, "text_probe_screen_changed");
                if(resourceReload!=null){
                    if(!resourceReload.isDone())return;
                    resourceReload.join();
                    if(minecraft.getOverlay()!=null)return;
                    require(StudioClientResources.generation()>initialResourceGeneration,"resource_reload_listener_did_not_advance");
                    require(StudioClientResources.shaderGeneration()>initialShaderGeneration,"coverage_shader_did_not_reload");
                    if(++reloadSettledFrames<SETTLE_TICKS)return;
                }
                samples.add(snapshot("settled_render"));
                finish(true, "observed_no_behavior_assertion");
            } catch (RuntimeException failure) { finish(false, failure.toString()); }
        }

        private JsonObject snapshot(String name) {
            Object surface = readField(screen, "ui");
            ControlTree tree = (ControlTree)readField(surface, "tree");
            var node = tree.focused().orElse(null);
            boolean textFocused = node != null && node.role() == ControlTree.Role.TEXT_FIELD;
            JsonObject sample = focusState(minecraft);
            sample.addProperty("name", name); sample.addProperty("real_client_tick", realTicks);
            Object worldSession = readField(screen, "worldSession");
            Object nativeChild = readField(worldSession, "child");
            JsonObject nativeState = new JsonObject();
            nativeState.addProperty("state", readField(worldSession, "state").toString());
            nativeState.addProperty("child_present", nativeChild != null);
            if (nativeChild != null) {
                nativeState.addProperty("child_class", nativeChild.getClass().getName());
                nativeState.addProperty("child_identity", Integer.toHexString(System.identityHashCode(nativeChild)));
            }
            nativeState.addProperty("child_focused", (Boolean)readField(worldSession, "focusedChild"));
            nativeState.addProperty("input_child_matches", nativeChild != null && nativeChild == readField(worldSession, "inputChild"));
            nativeState.addProperty("input_bounds_present", readField(worldSession, "inputBounds") != null);
            nativeState.addProperty("generation", (Long)readField(worldSession, "generation"));
            nativeState.addProperty("cancel_requested", (Boolean)readField(worldSession, "cancelRequested"));
            nativeState.addProperty("render_failed", (Boolean)readField(worldSession, "renderFailed"));
            sample.add("world_session", nativeState);
            sample.addProperty("actual_window_active", minecraft.isWindowActive());
            sample.addProperty("text_field_focused", textFocused);
            sample.addProperty("event_key", key); sample.addProperty("event_modifiers", modifiers);
            boolean physicalControl = WorkbenchScreen.hasControlDown(), physicalShift = WorkbenchScreen.hasShiftDown();
            sample.addProperty("physical_control", physicalControl); sample.addProperty("physical_shift", physicalShift);
            sample.addProperty("effective_control", (modifiers & GLFW.GLFW_MOD_CONTROL) != 0 || physicalControl);
            sample.addProperty("effective_shift", (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 || physicalShift);
            var json = new com.google.gson.Gson();
            var runtime = (net.ptcrys.fpsmatch.editor.common.ui.component.StudioRuntime)readField(surface, "runtime");
            var view = (net.ptcrys.fpsmatch.editor.common.ui.component.StudioView)readField(surface, "view");
            var components = (net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponents)readField(surface, "components");
            sample.add("layout_stats", json.toJsonTree(view.layoutStats()));
            sample.add("component_layout_stats", json.toJsonTree(components.view().layoutStats()));
            sample.add("text_cache_stats", json.toJsonTree(view.textLayout().cacheStats()));
            var textBackend=(net.ptcrys.fpsmatch.editor.common.text.java2d.StudioJava2dText)readField(surface,"textBackend");
            sample.add("text_backend_stats",json.toJsonTree(textBackend.stats()));
            sample.add("font_profile",json.toJsonTree(textBackend.fontProfile()));
            sample.add("renderer_stats", json.toJsonTree(readField(readField(surface, "renderer"), "last")));
            Object renderer=readField(surface,"renderer");
            try{
                var resources=renderer.getClass().getDeclaredMethod("resourceStats");resources.setAccessible(true);
                sample.add("renderer_resources",json.toJsonTree(resources.invoke(renderer)));
            }catch(ReflectiveOperationException failure){throw new IllegalStateException("renderer_resource_snapshot_failed",failure);}
            sample.add("last_resource_retirement",json.toJsonTree(readField(surface,"lastRetirement")));
            sample.addProperty("resource_generation",StudioClientResources.generation());
            sample.addProperty("coverage_shader_generation",StudioClientResources.shaderGeneration());
            sample.add("surface_resource_generation",json.toJsonTree(readField(surface,"resourceGeneration")));
            var inspectorProjection=(net.ptcrys.fpsmatch.editor.common.ui.workbench.WorkbenchInspectorProjection)readField(screen,"inspectorProjection");
            sample.add("inspector_projection_stats",json.toJsonTree(inspectorProjection.stats()));
            var contentProjection=(net.ptcrys.fpsmatch.editor.common.ui.workbench.WorkbenchContentProjection)readField(screen,"contentProjection");
            sample.add("content_projection_stats",json.toJsonTree(contentProjection.stats()));
            var contentSlots=(net.ptcrys.fpsmatch.editor.common.ui.component.StudioContentSlots<?>)readField(screen,"inspectorContents");
            sample.add("inspector_content_stats",json.toJsonTree(contentSlots.stats()));
            sample.addProperty("ui_frame_id", runtime.frame().frameId());
            sample.addProperty("mounted_controls", runtime.frame().mounts().size());
            var projection=(net.ptcrys.fpsmatch.editor.common.ui.workbench.WorkbenchListProjection)readField(screen,"listProjection");
            sample.add("list_projection_stats",json.toJsonTree(projection.stats()));
            sample.add("logical_focus",json.toJsonTree(runtime.input().currentLogicalFocus()));
            sample.add("requested_focus",json.toJsonTree(runtime.input().requestedFocus()));
            var lists=new JsonObject();
            for(var entry:((Map<?,?>)readField(screen,"listStates")).entrySet()){
                var window=(net.ptcrys.fpsmatch.editor.common.ui.component.StudioVirtualList.Window)readField(entry.getValue(),"window");
                var row=new JsonObject();row.addProperty("total",window.total());row.addProperty("first_visible",window.firstVisible());row.addProperty("end_visible",window.endVisible());
                row.addProperty("mounted_rows",window.mountedCount());row.addProperty("offset",((Number)readField(entry.getValue(),"offset")).intValue());
                lists.add(entry.getKey().toString(),row);
            }
            sample.add("virtual_lists",lists);
            if (node != null) { sample.addProperty("control_id", node.id()); sample.addProperty("readonly", node.readOnly()); }
            if (textFocused) {
                Object texts = runtime.input().visibleText();
                require(texts instanceof Map<?, ?>, "text_probe_visible_map_shape_changed");
                Object value = ((Map<?, ?>)texts).get(node.id());
                require(value instanceof SingleLineText, "text_probe_model_unavailable");
                SingleLineText text = (SingleLineText)value;
                Object undo = readField(text, "undo"), redo = readField(text, "redo");
                require(undo instanceof Collection<?> && redo instanceof Collection<?>, "text_probe_history_shape_changed");
                sample.addProperty("text_model_identity", Integer.toHexString(System.identityHashCode(text)));
                sample.addProperty("text", text.text()); sample.addProperty("semantic_value", node.value());
                sample.addProperty("caret", text.caret()); sample.addProperty("anchor", text.anchor());
                sample.addProperty("undo_depth", ((Collection<?>)undo).size());
                sample.addProperty("redo_depth", ((Collection<?>)redo).size());
            }
            return sample;
        }

        private void finish(boolean ok, String reason) {
            if (result.isDone()) return;
            JsonObject report = new JsonObject();
            report.addProperty("ok", ok); report.addProperty("status", ok ? "done" : "failed");
            report.addProperty("reason", reason); report.addProperty("action", action);
            report.addProperty("scope", "real_screen_key_events_and_read_only_text_history");
            report.addProperty("real_client_ticks", realTicks);
            report.addProperty("key_pressed_handled", pressed); report.addProperty("key_released_handled", released);
            report.addProperty("direct_text_write", false); report.addProperty("direct_history_write", false);
            report.addProperty("physical_modifier_write", false); report.addProperty("manual_tick", false);
            report.add("samples", samples);
            if (activeText == this) activeText = null;
            result.complete(report);
        }
    }

    private record Request(String action, int key, String keyName, int ticks, String order,
                           String release, double dx, double dy, int width, int height) {
        private boolean cancellation() { return List.of("cancel_menu", "cancel_text", "resize", "cancel_focus").contains(action); }
        private static Request read(JsonObject args) {
            for (String name : args.keySet()) {
                if (!List.of("action", "key", "ticks", "order", "release", "dx", "dy", "width", "height").contains(name))
                    throw new IllegalArgumentException("unsupported_probe_argument:" + name);
            }
            String action = string(args, "action", "move");
            String key = string(args, "key", "W").toUpperCase(Locale.ROOT);
            String order = string(args, "order", "button_first");
            String release = string(args, "release", "key");
            int ticks = args.has("ticks") ? args.get("ticks").getAsInt() : 6;
            double dx = args.has("dx") ? args.get("dx").getAsDouble() : 40;
            double dy = args.has("dy") ? args.get("dy").getAsDouble() : 12;
            int width = args.has("width") ? args.get("width").getAsInt() : 854;
            int height = args.has("height") ? args.get("height").getAsInt() : 480;
            require(List.of("move", "look", "stationary", "cancel_menu", "cancel_text", "resize", "cancel_focus").contains(action), "invalid_probe_action");
            require(action.equals("resize") || (!args.has("width") && !args.has("height")), "size_only_valid_for_resize");
            require((width == 854 && height == 480) || (width == 1280 && height == 720), "unsupported_probe_window_size");
            require(List.of("W", "A", "S", "D").contains(key), "invalid_probe_key");
            require(List.of("key_first", "button_first").contains(order), "invalid_probe_press_order");
            require(!List.of("cancel_menu", "cancel_text", "resize", "cancel_focus").contains(action)
                    || (key.equals("W") && order.equals("button_first")), "cancellation_probe_requires_button_first_w");
            require(List.of("key", "button").contains(release), "invalid_probe_release");
            require(ticks >= 1 && ticks <= 20, "invalid_probe_tick_count");
            require(Double.isFinite(dx) && Double.isFinite(dy) && Math.abs(dx) <= 100 && Math.abs(dy) <= 100,
                    "invalid_probe_drag_delta");
            require(!action.equals("look") || dx != 0, "look_requires_nonzero_horizontal_drag");
            int keyCode = switch (key) {
                case "W" -> GLFW.GLFW_KEY_W;
                case "A" -> GLFW.GLFW_KEY_A;
                case "S" -> GLFW.GLFW_KEY_S;
                case "D" -> GLFW.GLFW_KEY_D;
                default -> throw new IllegalArgumentException("invalid_probe_key");
            };
            return new Request(action, keyCode, key, ticks, order, release, dx, dy, width, height);
        }
    }

    private enum Phase { BASELINE, KEY_FIRST, KEY_FIRST_FRAME, HOLDING, MOVED_FRAME, SETTLING, STOPPED_FRAME,
        CANCEL_HOLDING, CANCEL_TRIGGER, CANCEL_WAIT, CANCEL_SETTLING, CANCEL_STOPPED, FINISHED }

    private static final class Session {
        private final Request request;
        private final CompletableFuture<JsonObject> result = new CompletableFuture<>();
        private final JsonArray samples = new JsonArray();
        private volatile String cancelReason;
        private final Minecraft minecraft = Minecraft.getInstance();
        private WorkbenchScreen screen;
        private WorkbenchWorldViewport viewport;
        private ClientLevel level;
        private LocalPlayer player;
        private ClientPacketListener connection;
        private WorkbenchWorldViewport.Frame before, moved;
        private Vec3 initialPlayer;
        private float initialPlayerYaw, initialPlayerPitch;
        private Phase phase = Phase.BASELINE;
        private int phaseTicks, realTicks, movementTicks;
        private double x, y;
        private boolean keyDown, buttonDown;
        private WorkbenchWorldViewport.Frame cancelled;
        private String controlId, originalText, typedText;
        private boolean menuOpened, textEdited, nativeFocusCycle, focusRestoreRequested, focusLossObserved;
        private boolean cancellationVerified, resizeOldFrameRevoked;
        private int inactiveTicks;

        private Session(Request request) { this.request = request; }

        private void begin() {
            try {
                require(minecraft.isSameThread(), "probe_not_on_client_thread");
                require(minecraft.screen != null && minecraft.screen.getClass() == WorkbenchScreen.class,
                        "exact_workbench_host_required");
                screen = (WorkbenchScreen)minecraft.screen;
                // Read-only access to the actual host-owned viewport. No test setter exists.
                Field field = WorkbenchScreen.class.getDeclaredField("viewport");
                field.setAccessible(true);
                viewport = (WorkbenchWorldViewport)field.get(screen);
                level = minecraft.level; player = minecraft.player; connection = minecraft.getConnection();
                require(viewport != null && level != null && player != null && connection != null, "ready_world_required");
                if (request.cancellation()) {
                    require(page().equals("EDITOR"), "cancellation_probe_requires_editor_page");
                    if (request.action().equals("resize")) {
                        require(!minecraft.getWindow().isFullscreen(), "resize_requires_windowed_client");
                        require(!requestedNativeSize(), "resize_requires_a_different_window_size");
                    }
                }
                checkContext();
            } catch (ReflectiveOperationException | RuntimeException failure) { fail(failure.toString()); }
        }

        private void tick() {
            try {
                checkContext();
                realTicks++;
                if (request.cancellation()) { cancellationTick(); return; }
                if (phase == Phase.KEY_FIRST && ++phaseTicks >= SETTLE_TICKS) phase = Phase.KEY_FIRST_FRAME;
                else if (phase == Phase.HOLDING && ++phaseTicks >= request.ticks()) {
                    movementTicks = phaseTicks;
                    if (request.action().equals("stationary") || request.release().equals("key")) releaseKey();
                    else releaseButton();
                    phase = Phase.MOVED_FRAME;
                } else if (phase == Phase.SETTLING && ++phaseTicks >= SETTLE_TICKS) phase = Phase.STOPPED_FRAME;
            } catch (RuntimeException failure) { fail(failure.toString()); }
        }

        private void rendered() {
            try {
                checkContext();
                if (request.cancellation() && phase != Phase.BASELINE) { cancellationRendered(); return; }
                var available = viewport.frame();
                if (available.isEmpty()) return; // A resize/loading failure times out; never substitutes a synthetic frame.
                WorkbenchWorldViewport.Frame frame = available.orElseThrow();
                if (phase != Phase.BASELINE) checkFrameIdentity(frame);
                switch (phase) {
                    case BASELINE -> {
                        before = frame;
                        initialPlayer = player.position();
                        initialPlayerYaw = player.getYRot(); initialPlayerPitch = player.getXRot();
                        var logical = frame.layout().logicalRect();
                        var scale = frame.layout().scaleTransform();
                        x = scale.toFramebufferX(logical.left() + logical.width() / 2.0) / minecraft.getWindow().getGuiScale();
                        y = scale.toFramebufferY(logical.top() + logical.height() / 2.0) / minecraft.getWindow().getGuiScale();
                        record("before", frame);
                        if (request.cancellation()) {
                            pressButton(); pressKey();
                            require(capturedButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && heldKeys().contains(request.key()),
                                    "cancellation_requires_real_capture_and_held_key");
                            phase = Phase.CANCEL_HOLDING; phaseTicks = 0;
                        } else if (request.action().equals("look")) {
                            pressButton();
                            require(screen.mouseDragged(x + request.dx(), y + request.dy(), GLFW.GLFW_MOUSE_BUTTON_RIGHT,
                                    request.dx(), request.dy()), "screen_did_not_handle_rmb_drag");
                            releaseButton();
                            phase = Phase.MOVED_FRAME;
                        } else if (request.action().equals("stationary")) {
                            pressKey(); phase = Phase.HOLDING; phaseTicks = 0;
                        } else if (request.order().equals("key_first")) {
                            pressKey(); phase = Phase.KEY_FIRST; phaseTicks = 0;
                        } else {
                            pressButton(); pressKey(); phase = Phase.HOLDING; phaseTicks = 0;
                        }
                    }
                    case KEY_FIRST_FRAME -> {
                        require(distance(before.cameraPosition(), frame.cameraPosition()) < EPSILON,
                                "wasd_moved_camera_without_rmb");
                        record("key_held_without_rmb", frame);
                        pressButton(); phase = Phase.HOLDING; phaseTicks = 0;
                    }
                    case MOVED_FRAME -> {
                        moved = frame;
                        record("after_input", frame);
                        checkMovement(frame);
                        phase = Phase.SETTLING; phaseTicks = 0;
                    }
                    case STOPPED_FRAME -> {
                        record("after_release", frame);
                        require(distance(moved.cameraPosition(), frame.cameraPosition()) < EPSILON,
                                "camera_continued_after_release");
                        require(moved.view().equals(frame.view()), "view_continued_after_release");
                        cleanup();
                        complete(true, "screen_events_and_actual_camera_frames_verified");
                    }
                    default -> { }
                }
            } catch (RuntimeException failure) { fail(failure.toString()); }
        }

        private void cancellationTick() {
            if (phase == Phase.CANCEL_HOLDING || phase == Phase.CANCEL_TRIGGER) {
                movementTicks++;
                if (++phaseTicks >= request.ticks()) phase = Phase.CANCEL_TRIGGER;
            } else if (phase == Phase.CANCEL_WAIT) {
                if (request.action().equals("cancel_focus")) {
                    if (!minecraft.isWindowActive() && !nativeFocused()) {
                        requireInputCleared();
                        if (!focusLossObserved) recordInputState("native_focus_lost_before_cleanup");
                        focusLossObserved = true;
                        if (++inactiveTicks >= SETTLE_TICKS && !focusRestoreRequested) {
                            focusRestoreRequested = true;
                            restoreNativeWindow();
                        }
                    }
                } else if (!request.action().equals("resize") || resizeCallbackObserved()) {
                    requireInputCleared();
                    if (request.action().equals("resize")) {
                        require(viewport.frame().filter(f -> f.frameId() == moved.frameId()).isEmpty(),
                                "old_frame_survived_native_resize_callback");
                        resizeOldFrameRevoked = true;
                    }
                }
            } else if (phase == Phase.CANCEL_SETTLING) {
                requireInputCleared();
                if (++phaseTicks >= SETTLE_TICKS) phase = Phase.CANCEL_STOPPED;
            }
        }

        private void cancellationRendered() {
            if (phase == Phase.CANCEL_HOLDING) return;
            if (phase == Phase.CANCEL_WAIT && request.action().equals("cancel_focus")
                    && (!focusRestoreRequested || !minecraft.isWindowActive() || !nativeFocused())) return;
            if (phase == Phase.CANCEL_WAIT && request.action().equals("resize") && !resizeCallbackObserved()) return;
            var available = viewport.frame();
            if (available.isEmpty()) return;
            var frame = available.orElseThrow();
            if (phase == Phase.CANCEL_TRIGGER) {
                checkFrameIdentity(frame);
                require(capturedButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && heldKeys().contains(request.key()),
                        "capture_or_key_lost_before_cancellation_trigger");
                Vec3 delta = position(frame.cameraPosition()).subtract(position(before.cameraPosition()));
                require(movementTicks >= request.ticks()
                                && Math.abs(delta.length() - movementTicks * 0.5) < EPSILON,
                        "cancellation_baseline_requires_actual_camera_movement");
                Vec3 direction = switch (request.keyName()) {
                    case "W" -> column(before.inverseView(), 2).scale(-1);
                    case "S" -> column(before.inverseView(), 2);
                    case "A" -> column(before.inverseView(), 0).scale(-1);
                    case "D" -> column(before.inverseView(), 0);
                    default -> throw new IllegalStateException("unsupported_key");
                };
                require(delta.normalize().dot(direction.normalize()) > 0.9999 && before.view().equals(frame.view()),
                        "cancellation_baseline_camera_direction_wrong");
                moved = frame;
                record("held_before_cancel", frame);
                recordInputState("held_before_cancel");
                phase = Phase.CANCEL_WAIT;
                triggerCancellation();
                return;
            }
            if (phase != Phase.CANCEL_WAIT && phase != Phase.CANCEL_SETTLING && phase != Phase.CANCEL_STOPPED) return;
            requireInputCleared();
            require(readField(screen, "viewport") == viewport, "resize_replaced_editor_camera_owner");
            require(frame.producer().equals(moved.producer()) && frame.dimension().equals(moved.dimension())
                    && frame.frameId() > moved.frameId(), "cancellation_requires_new_frame_from_same_viewport");
            require(distance(moved.cameraPosition(), frame.cameraPosition()) < EPSILON
                    && moved.view().equals(frame.view()), "camera_changed_after_cancellation_or_resize");
            if (request.action().equals("resize")) {
                require(frame.layout().layoutGeneration() > moved.layout().layoutGeneration()
                        && frame.targetGeneration() > moved.targetGeneration(), "resize_did_not_replace_layout_and_target");
                require(frame.layout().scaleTransform().framebufferWidth() == minecraft.getWindow().getWidth()
                        && frame.layout().scaleTransform().framebufferHeight() == minecraft.getWindow().getHeight(),
                        "resize_frame_dimensions_do_not_match_actual_callback");
                resizeOldFrameRevoked = true;
            }
            if (request.action().equals("cancel_menu")) require(!page().equals("EDITOR"), "menu_did_not_open");
            if (request.action().equals("cancel_text")) {
                ControlTree.Node text = control(controlId);
                require(text.value().equals(typedText) && focusedControl().equals(controlId), "text_input_not_observed_in_real_control");
            }
            if (request.action().equals("cancel_focus")) require(focusLossObserved && inactiveTicks >= SETTLE_TICKS,
                    "real_focus_loss_not_observed");
            if (phase == Phase.CANCEL_WAIT) {
                cancelled = frame;
                record("cancelled_before_cleanup", frame);
                recordInputState("cancelled_before_cleanup");
                phase = Phase.CANCEL_SETTLING; phaseTicks = 0;
            } else if (phase == Phase.CANCEL_STOPPED) {
                require(frame.frameId() > cancelled.frameId(), "new_post_cancellation_frame_required");
                record("still_stopped_before_cleanup", frame);
                recordInputState("still_stopped_before_cleanup");
                cancellationVerified = true;
                cleanup();
                complete(true, "actual_cancellation_and_camera_frames_verified_before_cleanup");
            }
        }

        private void triggerCancellation() {
            switch (request.action()) {
                case "cancel_menu" -> {
                    var node = screen.semanticTree().stream().filter(n -> n.id().startsWith("menu/")
                            && n.enabled() && n.focusable()).findFirst().orElseThrow(
                            () -> new IllegalStateException("visible_menu_control_required"));
                    controlId = node.id();
                    clickControl(node);
                    menuOpened = !page().equals("EDITOR");
                    require(menuOpened, "real_menu_click_did_not_open_menu");
                    requireInputCleared();
                }
                case "cancel_text" -> {
                    var node = screen.semanticTree().stream().filter(n -> n.role() == ControlTree.Role.TEXT_FIELD
                            && n.enabled() && !n.readOnly() && n.focusable()
                            && (n.id().startsWith("object-search/") || n.id().startsWith("resources/search/")
                            || n.id().startsWith("inspector-search/"))).findFirst().orElseThrow(
                            () -> new IllegalStateException("visible_nonpersistent_search_field_required"));
                    controlId = node.id(); originalText = node.value();
                    clickControl(node);
                    // Assert before any following UI keystroke could independently cancel capture.
                    requireInputCleared();
                    require(focusedControl().equals(controlId), "search_field_did_not_receive_focus");
                    typedText = originalText.equals("q82_probe") ? "q82_probe_2" : "q82_probe";
                    textEdited = true;
                    replaceFocusedText(typedText);
                    // A movement key held while text owns focus must never re-arm the viewport.
                    screen.keyPressed(request.key(), 0, 0);
                    requireInputCleared();
                }
                case "resize" -> {
                    GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), request.width(), request.height());
                    // Native dimensions may change before GLFW dispatches the callback. The
                    // following ticks/frames wait for Minecraft's measured framebuffer to change.
                }
                case "cancel_focus" -> {
                    require(nativeFocused(), "native_window_focus_required");
                    nativeFocusCycle = true;
                    GLFW.glfwIconifyWindow(minecraft.getWindow().getWindow());
                }
                default -> throw new IllegalStateException("invalid_cancellation_action");
            }
        }

        private int capturedButton() { return (Integer)readField(viewport, "capturedButton"); }
        private Set<?> heldKeys() { return (Set<?>)readField(viewport, "heldMovementKeys"); }
        private void requireInputCleared() {
            require(capturedButton() == -1 && heldKeys().isEmpty(), "viewport_capture_or_held_keys_survived_cancellation");
            require(keyDown && buttonDown, "probe_released_input_before_cancellation_verification");
        }
        private String page() { return ((Enum<?>)readField(screen, "page")).name(); }
        private String focusedControl() {
            Object surface = readField(screen, "ui");
            return ((ControlTree)readField(surface, "tree")).focused().map(ControlTree.Node::id).orElse("");
        }
        private ControlTree.Node control(String id) {
            return screen.semanticTree().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow(
                    () -> new IllegalStateException("control_no_longer_visible:" + id));
        }
        private void clickControl(ControlTree.Node node) {
            var bounds = node.visibleBounds();
            double density = moved.layout().scaleTransform().xScale() / minecraft.getWindow().getGuiScale();
            double controlX = (bounds.x() + bounds.width() / 2.0) * density;
            double controlY = (bounds.y() + bounds.height() / 2.0) * density;
            require(screen.mouseClicked(controlX, controlY, GLFW.GLFW_MOUSE_BUTTON_LEFT), "control_click_not_handled");
            screen.mouseReleased(controlX, controlY, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        }
        private void replaceFocusedText(String value) {
            screen.keyPressed(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL);
            screen.keyReleased(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL);
            screen.keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0);
            screen.keyReleased(GLFW.GLFW_KEY_BACKSPACE, 0, 0);
            for (char character : value.toCharArray()) screen.charTyped(character, 0);
        }
        private boolean nativeFocused() {
            return GLFW.glfwGetWindowAttrib(minecraft.getWindow().getWindow(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
        }
        private boolean requestedNativeSize() {
            int[] width = new int[1], height = new int[1];
            GLFW.glfwGetWindowSize(minecraft.getWindow().getWindow(), width, height);
            return width[0] == request.width() && height[0] == request.height();
        }
        private boolean resizeCallbackObserved() {
            return requestedNativeSize() && (minecraft.getWindow().getWidth() != moved.layout().scaleTransform().framebufferWidth()
                    || minecraft.getWindow().getHeight() != moved.layout().scaleTransform().framebufferHeight());
        }
        private void restoreNativeWindow() {
            GLFW.glfwRestoreWindow(minecraft.getWindow().getWindow());
            GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
        }
        private void recordInputState(String name) {
            JsonObject sample = new JsonObject();
            sample.addProperty("name", name); sample.addProperty("client_tick", realTicks);
            sample.addProperty("actual_window_active", minecraft.isWindowActive());
            sample.addProperty("native_window_focused", nativeFocused());
            sample.addProperty("captured_button", capturedButton());
            sample.addProperty("held_movement_key_count", heldKeys().size());
            sample.addProperty("probe_key_still_down", keyDown); sample.addProperty("probe_button_still_down", buttonDown);
            sample.addProperty("page", page());
            samples.add(sample);
        }

        private void checkContext() {
            require(cancelReason == null, cancelReason == null ? "" : cancelReason);
            require(enabled(), "probe_flags_revoked");
            require(minecraft.isSameThread(), "probe_not_on_client_thread");
            // Only the dedicated native focus-loss phase permits actual inactivity.
            require(minecraft.isWindowActive() || (request.action().equals("cancel_focus") && nativeFocusCycle
                    && phase == Phase.CANCEL_WAIT), "actual_window_focus_lost_or_inactive");
            require(screen != null && minecraft.screen == screen, "workbench_host_changed");
            require(minecraft.getOverlay() == null, "native_overlay_took_input");
            require(minecraft.level == level && minecraft.player == player && minecraft.getConnection() == connection,
                    "world_session_changed");
            if (initialPlayer != null) {
                require(initialPlayer.distanceTo(player.position()) < EPSILON, "player_moved_during_camera_probe");
                require(player.getYRot() == initialPlayerYaw && player.getXRot() == initialPlayerPitch,
                        "player_rotation_changed_during_camera_probe");
            }
        }

        private void checkFrameIdentity(WorkbenchWorldViewport.Frame frame) {
            require(before.producer().equals(frame.producer()) && before.dimension().equals(frame.dimension())
                    && before.layout().equals(frame.layout()) && before.targetGeneration() == frame.targetGeneration(),
                    "viewport_identity_or_layout_changed");
            require(frame.frameId() > before.frameId(), "new_real_rendered_frame_required");
        }

        private void checkMovement(WorkbenchWorldViewport.Frame frame) {
            Vec3 delta = position(frame.cameraPosition()).subtract(position(before.cameraPosition()));
            if (request.action().equals("move")) {
                require(movementTicks == request.ticks(), "unexpected_real_movement_tick_count");
                require(Math.abs(delta.length() - request.ticks() * 0.5) < EPSILON, "camera_distance_does_not_match_real_ticks");
                Vec3 forward = column(before.inverseView(), 2).scale(-1).normalize();
                Vec3 right = column(before.inverseView(), 0).normalize();
                Vec3 expected = switch (request.keyName()) {
                    case "W" -> forward;
                    case "S" -> forward.scale(-1);
                    case "A" -> right.scale(-1);
                    case "D" -> right;
                    default -> throw new IllegalStateException("unsupported_key");
                };
                require(delta.normalize().dot(expected) > 0.9999, "camera_moved_in_wrong_view_direction");
                require(before.view().equals(frame.view()), "movement_changed_view_orientation");
            } else {
                require(delta.length() < EPSILON, "camera_translated_without_movement_gesture");
                if (request.action().equals("look")) require(!before.view().equals(frame.view()), "rmb_drag_did_not_change_real_view");
                else require(before.view().equals(frame.view()), "wasd_without_rmb_changed_view");
            }
        }

        private void pressKey() { keyDown = true; screen.keyPressed(request.key(), 0, 0); }
        private void releaseKey() {
            if (keyDown) { screen.keyReleased(request.key(), 0, 0); keyDown = false; }
        }
        private void pressButton() {
            buttonDown = true;
            require(screen.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_RIGHT), "screen_did_not_handle_rmb_press");
        }
        private void releaseButton() {
            if (buttonDown) { screen.mouseReleased(x, y, GLFW.GLFW_MOUSE_BUTTON_RIGHT); buttonDown = false; }
        }
        private void cleanup() {
            RuntimeException failure = null;
            try { releaseKey(); } catch (RuntimeException error) { failure = error; }
            try { releaseButton(); } catch (RuntimeException error) {
                if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
            }
            try {
                if (minecraft.screen == screen && screen != null) {
                    if (textEdited && focusedControl().equals(controlId)) {
                        replaceFocusedText(originalText);
                        textEdited = false;
                    }
                    if (menuOpened && !page().equals("EDITOR")) {
                        screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                        screen.keyReleased(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                        menuOpened = false;
                    }
                }
            } catch (RuntimeException error) {
                if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
            }
            try {
                if (nativeFocusCycle && (!minecraft.isWindowActive() || !nativeFocused())) restoreNativeWindow();
            } catch (RuntimeException error) {
                if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }

        private void fail(String reason) {
            if (result.isDone()) return;
            try { cleanup(); }
            catch (RuntimeException cleanupFailure) { reason += "; input_cleanup_failed:" + cleanupFailure; }
            complete(false, reason);
        }

        private void complete(boolean ok, String reason) {
            phase = Phase.FINISHED;
            JsonObject report = new JsonObject();
            report.addProperty("ok", ok); report.addProperty("status", ok ? "done" : "failed");
            report.addProperty("reason", reason);
            report.addProperty("scope", "real_client_screen_events_and_rendered_camera_frames");
            report.addProperty("agent_registry_loader", ViewportAgentBridge.registrationIdentity());
            report.addProperty("action", request.action()); report.addProperty("key", request.keyName());
            report.addProperty("press_order", request.order()); report.addProperty("release", request.release());
            report.addProperty("requested_hold_ticks", request.ticks()); report.addProperty("real_client_ticks", realTicks);
            report.addProperty("movement_ticks", movementTicks);
            report.addProperty("drag_dx_gui_scaled_pixels", request.dx()); report.addProperty("drag_dy_gui_scaled_pixels", request.dy());
            report.addProperty("synthetic_focus", false); report.addProperty("global_desktop_input", false);
            report.addProperty("direct_camera_write", false); report.addProperty("manual_tick", false);
            report.addProperty("inputs_released", !keyDown && !buttonDown);
            if (request.cancellation()) {
                report.addProperty("cancellation_verified_before_cleanup", cancellationVerified);
                report.addProperty("control_id", controlId);
                report.addProperty("native_focus_loss_observed", focusLossObserved);
                report.addProperty("native_focus_inactive_ticks", inactiveTicks);
                report.addProperty("old_resize_frame_revoked", resizeOldFrameRevoked);
                if (request.action().equals("resize")) {
                    report.addProperty("requested_native_width", request.width());
                    report.addProperty("requested_native_height", request.height());
                    report.addProperty("window_left_at_requested_size_for_screenshot", ok);
                }
            }
            report.addProperty("visual_verification", "requires_packaged_cli_screenshots_and_actual_review");
            report.add("samples", samples);
            if (active == this) active = null;
            result.complete(report);
        }

        private void record(String name, WorkbenchWorldViewport.Frame frame) {
            JsonObject sample = new JsonObject();
            sample.addProperty("name", name); sample.addProperty("client_tick", realTicks);
            sample.addProperty("actual_window_active", minecraft.isWindowActive());
            sample.addProperty("frame_id", frame.frameId()); sample.addProperty("render_sequence", frame.renderSequence());
            sample.addProperty("target_generation", frame.targetGeneration()); sample.addProperty("camera_generation", frame.cameraGeneration());
            sample.addProperty("layout_generation", frame.layout().layoutGeneration());
            sample.addProperty("framebuffer_width", minecraft.getWindow().getWidth());
            sample.addProperty("framebuffer_height", minecraft.getWindow().getHeight());
            sample.addProperty("producer", frame.producer().toString()); sample.addProperty("dimension", frame.dimension());
            sample.addProperty("client_game_time", frame.clientGameTime());
            sample.add("camera_position", vector(position(frame.cameraPosition())));
            sample.add("player_position", vector(player.position()));
            sample.addProperty("player_yaw", player.getYRot()); sample.addProperty("player_pitch", player.getXRot());
            sample.add("view_column_major", numbers(frame.view().columnMajor()));
            sample.add("projection_column_major", numbers(frame.projection().columnMajor()));
            var rect = frame.layout().framebufferRect();
            JsonObject pixels = new JsonObject();
            pixels.addProperty("x", rect.x()); pixels.addProperty("y", rect.y());
            pixels.addProperty("width", rect.width()); pixels.addProperty("height", rect.height());
            sample.add("viewport_framebuffer_pixels", pixels);
            sample.addProperty("gui_scale", minecraft.getWindow().getGuiScale());
            var logical = frame.layout().logicalRect();
            viewport.pick(logical.left() + logical.width() / 2.0, logical.top() + logical.height() / 2.0).ifPresent(pick -> {
                JsonObject hit = new JsonObject();
                hit.addProperty("type", pick.hit().getType().name());
                hit.add("location", vector(pick.hit().getLocation()));
                hit.addProperty("block_x", pick.hit().getBlockPos().getX());
                hit.addProperty("block_y", pick.hit().getBlockPos().getY());
                hit.addProperty("block_z", pick.hit().getBlockPos().getZ());
                hit.addProperty("ray_max_distance", pick.ray().maxDistance());
                sample.add("center_pick", hit);
            });
            samples.add(sample);
        }
    }

    private static JsonObject choices(String... values) {
        JsonObject schema = new JsonObject(); schema.addProperty("type", "string");
        JsonArray choices = new JsonArray(); for (String value : values) choices.add(value);
        schema.add("enum", choices); return schema;
    }
    /** Read only: test evidence never changes fields in the screen, viewport, or camera. */
    private static Object readField(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("probe_field_unavailable:" + name, failure);
        }
    }
    private static String string(JsonObject args, String key, String fallback) {
        return args.has(key) ? args.get(key).getAsString() : fallback;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static Vec3 position(Position value) { return new Vec3(value.x(), value.y(), value.z()); }
    private static double distance(Position a, Position b) { return position(a).distanceTo(position(b)); }
    private static Vec3 column(FrameMatrix matrix, int column) {
        return new Vec3(matrix.get(0, column), matrix.get(1, column), matrix.get(2, column));
    }
    private static JsonArray vector(Vec3 value) { return numbers(new double[]{value.x, value.y, value.z}); }
    private static JsonArray numbers(double[] values) {
        JsonArray result = new JsonArray(); for (double value : values) result.add(value); return result;
    }
}
