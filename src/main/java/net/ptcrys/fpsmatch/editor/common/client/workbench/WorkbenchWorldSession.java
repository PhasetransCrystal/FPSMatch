package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelSummary;

import com.mojang.logging.LogUtils;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Owns one editor's native world entry. The editor remains the actual Minecraft screen. */
public final class WorkbenchWorldSession implements AutoCloseable {

    enum State {
        OFFLINE,
        SELECTING,
        LOADING,
        CONNECTED,
        LEAVING,
        FAILED
    }

    private static WorkbenchWorldSession active;
    private final Minecraft minecraft = Minecraft.getInstance();
    private final WorkbenchScreen root;
    private final Runnable changed;
    private final WorkbenchNativeSurface nativeSurface = new WorkbenchNativeSurface();
    private State state = State.OFFLINE;
    private Screen child;
    private Screen failedRenderChild;
    private Overlay failedRenderOverlay;
    private Overlay observedOverlay;
    private boolean renderFailed;
    private CompletableFuture<List<LevelSummary>> listing;
    private List<LevelSummary> worlds = List.of();
    private Component failure;
    private String selected, search = "", targetLabel = "";
    private long generation;
    private boolean cancelRequested, receiving, nativeRendering, focusedChild;
    private long overlayRenderCalls;
    private int nativeLoadDepth, childWidth, childHeight;
    private IntegratedServer ownedServer;
    private Connection ownedPendingConnection;
    private boolean leaveClearFailure, finishingLeave;
    private CompletableFuture<Void> leavingPacks;
    private Runnable deferred;
    private ControlTree.Bounds bounds, inputBounds;
    private Screen inputChild;
    private long inputGeneration;
    private final Map<Screen, Long> ownedScreens = new WeakHashMap<>();
    private CreationAttempt creation;
    private Runnable creationReturn;
    private Predicate<String> entryPolicy = id -> true;

    record CreatedWorld(String levelId, Path root, String displayName) {}

    private static final class CreationAttempt {

        final long ticket;
        final String suggestedName;
        final Consumer<CreatedWorld> completed;
        final Runnable cancelled;
        CreateWorldScreen screen;
        boolean submitted;

        CreationAttempt(long ticket, String suggestedName, Consumer<CreatedWorld> completed, Runnable cancelled) {
            this.ticket = ticket;
            this.suggestedName = suggestedName;
            this.completed = completed;
            this.cancelled = cancelled;
        }
    }

    WorkbenchWorldSession(WorkbenchScreen root, Runnable changed) {
        this.root = root;
        this.changed = changed;
    }

    void attach() {
        active = this;
        if (minecraft.level != null && minecraft.player != null && state == State.OFFLINE) state = State.CONNECTED;
    }

    boolean preparing() {
        return state == State.LOADING && receiving && !cancelRequested && minecraft.level != null && minecraft.player != null && minecraft.getConnection() != null;
    }

    boolean cancellationRequested() {
        return cancelRequested;
    }

    boolean ready() {
        return state == State.CONNECTED && child == null && !cancelRequested && minecraft.level != null && minecraft.player != null && minecraft.getConnection() != null && minecraft.getOverlay() == null;
    }

    State state() {
        return state;
    }

    boolean nativeRendering() {
        return nativeRendering;
    }

    boolean selecting() {
        return state == State.SELECTING;
    }

    boolean hasNative() {
        return child != null || minecraft.getOverlay() != null;
    }

    void refreshOverlay() {
        var current = minecraft.getOverlay();
        if (observedOverlay != current) {
            observedOverlay = current;
            inputBounds = null;
            inputChild = null;
            changed.run();
        }
    }

    boolean loading() {
        return state == State.LOADING || state == State.LEAVING;
    }

    boolean reading() {
        return listing != null;
    }

    boolean canSelect() {
        return creation == null && creationReturn == null && ownedServer == null && !loading() && child == null && minecraft.level == null && minecraft.getSingleplayerServer() == null && minecraft.getOverlay() == null;
    }

    boolean creatingWorld() {
        return creation != null || creationReturn != null;
    }

    boolean canCancel() {
        return state == State.LOADING && !cancelRequested;
    }

    Optional<Component> failure() {
        return Optional.ofNullable(failure);
    }

    List<LevelSummary> worlds() {
        return worlds;
    }

    String selected() {
        return selected;
    }

    String search() {
        return search;
    }

    String targetLabel() {
        return targetLabel;
    }

    void search(String value) {
        search = value;
        changed.run();
    }

    void entryPolicy(Predicate<String> policy) {
        entryPolicy = Objects.requireNonNull(policy);
    }

    void select(String id) {
        if (id != null && entryPolicy.test(id) && worlds.stream().anyMatch(w -> w.getLevelId().equals(id))) selected = id;
        changed.run();
    }

    void openSelection() {
        if (!canSelect()) return;
        state = State.SELECTING;
        failure = null;
        refresh();
        changed.run();
    }

    void cancelSelection() {
        if (state == State.SELECTING) {
            state = State.OFFLINE;
            changed.run();
        }
    }

    /** Opens vanilla's complete creation workflow; the caller retains its project draft. */
    boolean createWorld(String suggestedName, Consumer<CreatedWorld> completed, Runnable cancelled) {
        Objects.requireNonNull(completed);
        Objects.requireNonNull(cancelled);
        if (!canSelect()) return false;
        long ticket = ++generation;
        creation = new CreationAttempt(ticket, Objects.requireNonNullElse(suggestedName, ""), completed, cancelled);
        listing = null;
        inputBounds = null;
        inputChild = null;
        cancelRequested = false;
        receiving = false;
        failure = null;
        state = State.LOADING;
        targetLabel = creation.suggestedName;
        setChild(new GenericDirtMessageScreen(Component.translatable("createWorld.preparing")));
        deferred = () -> {
            if (creation == null || ticket != generation) return;
            if (cancelRequested) {
                finishOffline(true);
                return;
            }
            // openFresh force-renders and blocks for resources. Keep its nested ticks inside
            // the same load scope so cancellation cannot retire this attempt halfway through.
            nativeLoadDepth++;
            try {
                CreateWorldScreen.openFresh(minecraft, new SessionReturnScreen(ticket));
            } catch (RuntimeException error) {
                loadFailed(error);
            } finally {
                nativeLoadDepth--;
                captureOwnership();
            }
        };
        changed.run();
        return true;
    }

    private void configureCreation(CreateWorldScreen screen) {
        if (creation == null || creation.ticket != generation || creation.screen != null) return;
        creation.screen = screen;
        var settings = screen.getUiState();
        if (!creation.suggestedName.isBlank()) settings.setName(creation.suggestedName);
        settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
        settings.setDifficulty(Difficulty.PEACEFUL);
        settings.setAllowCheats(true);
        settings.getNormalPresetList().stream().filter(preset -> preset.preset() != null && preset.preset().is(WorldPresets.FLAT)).findFirst().ifPresent(settings::setWorldType);
        settings.setGenerateStructures(false);
        settings.setBonusChest(false);
    }

    private void completeCreation() {
        if (creation == null || nativeLoadDepth != 0 || !ready()) return;
        var attempt = creation;
        CreatedWorld result;
        try {
            if (attempt.ticket != generation || attempt.screen == null || ownedServer == null) throw new IllegalStateException("Created world has no session owner");
            String id = attempt.screen.getUiState().getTargetFolder();
            Path base = minecraft.getLevelSource().getBaseDir().toRealPath();
            Path expected = base.resolve(id).normalize();
            Path actual = ownedServer.getWorldPath(LevelResource.ROOT).toRealPath();
            if (!expected.getParent().equals(base) || !actual.getParent().equals(base) || !Files.isSameFile(expected, actual) || !Files.isRegularFile(actual.resolve("level.dat"))) throw new IllegalStateException("Created world does not match its native save directory");
            result = new CreatedWorld(id, actual, ownedServer.getWorldData().getLevelName());
        } catch (Exception error) {
            fail(error, "world_load_failed");
            leave(false);
            return;
        }
        creation = null;
        ++generation;
        targetLabel = result.displayName();
        creationReturn = () -> attempt.completed.accept(result);
        changed.run();
    }

    private void returnFromCreation() {
        if (creationReturn == null || nativeLoadDepth != 0) return;
        var callback = creationReturn;
        creationReturn = null;
        if (active == this && minecraft.screen == root) callback.run();
    }

    void refresh() {
        if (listing != null) return;
        try {
            var candidates = minecraft.getLevelSource().findLevelCandidates();
            listing = candidates.isEmpty() ? CompletableFuture.completedFuture(List.of()) : minecraft.getLevelSource().loadLevelSummaries(candidates);
        } catch (Exception error) {
            fail(error, "world_list_failed");
        }
        changed.run();
    }

    void tick() {
        if (listing != null && listing.isDone()) {
            var complete = listing;
            listing = null;
            try {
                worlds = List.copyOf(complete.join());
                if (worlds.stream().noneMatch(w -> w.getLevelId().equals(selected))) selected = null;
                failure = null;
            } catch (RuntimeException error) {
                fail(error, "world_list_failed");
            }
            changed.run();
        }
        captureOwnership();
        if (state == State.LEAVING) {
            pollLeave();
            returnFromCreation();
            return;
        }
        if (child != null && minecraft.getOverlay() == null) {
            Screen current = child;
            nativeInput(() -> {
                current.tick();
                return true;
            });
        }
        if (state == State.CONNECTED && (minecraft.level == null || minecraft.player == null)) {
            boolean clearFailure = creation == null || cancelRequested;
            if (!clearFailure && failure == null) failure = Component.translatable("editor.workbench.world_load_failed");
            if (ownedServer != null && !ownedServer.isShutdown()) leave(clearFailure);
            else {
                ownedServer = null;
                ownedPendingConnection = null;
                if (creation != null) finishOffline(clearFailure);
                else {
                    state = State.OFFLINE;
                    changed.run();
                }
            }
        }
        if (nativeLoadDepth == 0 && deferred != null) {
            Runnable work = deferred;
            deferred = null;
            work.run();
        }
        if (cancelRequested && nativeLoadDepth == 0 && ownedServer != null) leave();
        if (cancelRequested && nativeLoadDepth == 0 && creation != null && child == creation.screen) nativeInput(() -> {
            creation.screen.popScreen();
            return true;
        });
        completeCreation();
        returnFromCreation();
    }

    void enterSelected() {
        if (state != State.SELECTING || listing != null || !canSelect()) return;
        var chosen = worlds.stream().filter(w -> w.getLevelId().equals(selected)).findFirst().orElse(null);
        if (chosen == null || chosen.isDisabled() || !entryPolicy.test(chosen.getLevelId())) return;
        final long ticket = ++generation;
        Screen returnScreen = new SessionReturnScreen(ticket);
        inputBounds = null;
        inputChild = null;
        cancelRequested = false;
        receiving = false;
        failure = null;
        state = State.LOADING;
        targetLabel = chosen.getLevelName();
        if (chosen instanceof LevelSummary.SymlinkLevelSummary) {
            setChild(new SymlinkWarningScreen(returnScreen));
            changed.run();
            return;
        }
        var backup = chosen.backupStatus();
        if (backup.shouldBackup()) {
            var title = Component.translatable("selectWorld.backupQuestion." + backup.getTranslationKey());
            var message = Component.translatable("selectWorld.backupWarning." + backup.getTranslationKey(), chosen.getWorldVersionName(), SharedConstants.getCurrentVersion().getName());
            setChild(new BackupConfirmScreen(returnScreen, (save, erase) -> {
                if (ticket != generation) return;
                if (save) try (var access = minecraft.getLevelSource().validateAndCreateAccess(chosen.getLevelId())) {
                    EditWorldScreen.makeBackupAndShowToast(access);
                } catch (Exception error) {
                    fail(error, "world_backup_failed");
                    finishOffline(false);
                    return;
                }
                deferLoad(ticket, chosen.getLevelId());
            }, title, message, false));
        } else if (chosen.askToOpenWorld()) {
            setChild(new ConfirmScreen(accept -> {
                if (ticket != generation) return;
                if (accept) deferLoad(ticket, chosen.getLevelId());
                else finishOffline(true);
            }, Component.translatable("selectWorld.versionQuestion"), Component.translatable("selectWorld.versionWarning", chosen.getWorldVersionName()), Component.translatable("selectWorld.versionJoinButton"), CommonComponents.GUI_CANCEL));
        } else deferLoad(ticket, chosen.getLevelId());
        changed.run();
    }

    private void deferLoad(long ticket, String id) {
        setChild(new GenericDirtMessageScreen(Component.translatable("selectWorld.data_read")));
        deferred = () -> {
            if (ticket != generation) return;
            if (cancelRequested || !entryPolicy.test(id)) {
                finishOffline(true);
                return;
            }
            try {
                minecraft.forceSetScreen(child);
                if (ticket != generation || cancelRequested || !entryPolicy.test(id)) {
                    finishOffline(true);
                    return;
                }
                minecraft.createWorldOpenFlows().loadLevel(new SessionReturnScreen(ticket), id);
            } catch (RuntimeException error) {
                loadFailed(error);
            }
        };
        changed.run();
    }

    void requestCancel() {
        if (!canCancel()) return;
        cancelRequested = true;
        captureOwnership();
        // Login rejection screens also reject Escape. Once a native server exists, cancellation
        // owns its shutdown regardless of whether the client has received a level yet.
        if (nativeLoadDepth == 0 && ownedServer != null) {
            leave(true);
            return;
        }
        // This native failure page deliberately rejects Escape. Its Back button only returns
        // to null after WorldOpenFlows has closed the failed stem/access, so use that same route.
        if (nativeLoadDepth == 0 && child instanceof DatapackLoadFailureScreen) {
            route(null);
            changed.run();
            return;
        }
        if (nativeLoadDepth == 0 && child != null && !(child instanceof LevelLoadingScreen) && !(child instanceof ReceivingLevelScreen) && !(child instanceof ProgressScreen) && !(child instanceof GenericDirtMessageScreen)) nativeInput(() -> child.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        changed.run();
    }

    void leave() {
        leave(true);
    }

    private void leave(boolean clearFailure) {
        if (nativeLoadDepth > 0) {
            cancelRequested = true;
            changed.run();
            return;
        }
        if (state == State.LEAVING) {
            leaveClearFailure &= clearFailure;
            return;
        }
        captureOwnership();
        state = State.LEAVING;
        leaveClearFailure = clearFailure;
        cancelRequested = true;
        leavingPacks = null;
        root.cancelWorldInput();
        setChild(new ProgressScreen(true));
        changed.run();
        try {
            if (ownedPendingConnection != null) ownedPendingConnection.disconnect(Component.translatable("menu.disconnect"));
            if (minecraft.level != null) minecraft.level.disconnect();
            // clearLevel only waits when a ClientLevel exists. The server owns its world lock
            // even before login completes; retain that owner and wait across normal client ticks.
            if (ownedServer != null && !ownedServer.isShutdown()) {
                var stopping = ownedServer;
                stopping.execute(() -> stopping.halt(false));
            }
            pollLeave();
        } catch (RuntimeException error) {
            fail(error, "world_leave_failed");
            leaveClearFailure = false;
        }
    }

    private void captureOwnership() {
        if (minecraft.getSingleplayerServer() != null) ownedServer = minecraft.getSingleplayerServer();
        var pending = ((MinecraftAccess) minecraft).fpsmatch$pendingConnection();
        if (pending != null) ownedPendingConnection = pending;
    }

    private void pollLeave() {
        if (nativeLoadDepth > 0 || finishingLeave || ownedServer != null && !ownedServer.isShutdown()) return;
        try {
            // Capture the first reload future before clearLevel can discard it. A second
            // clearServerPack call sees a null serverPack and would falsely appear complete.
            if (leavingPacks == null) leavingPacks = minecraft.getDownloadedPackSource().clearServerPack();
            if (!leavingPacks.isDone()) return;
            try {
                leavingPacks.join();
            } catch (RuntimeException error) {
                fail(error, "world_leave_failed");
                leaveClearFailure = false;
            }
            finishingLeave = true;
            minecraft.clearLevel(new ProgressScreen(true));
            ownedServer = null;
            ownedPendingConnection = null;
            leavingPacks = null;
            finishOffline(leaveClearFailure);
        } catch (RuntimeException error) {
            fail(error, "world_leave_failed");
            state = State.FAILED;
            changed.run();
        } finally {
            finishingLeave = false;
        }
    }

    /** Access is implemented by the existing Minecraft session mixin. */
    public interface MinecraftAccess {

        Connection fpsmatch$pendingConnection();
    }

    public static boolean interceptClearLevel() {
        var session = active;
        if (session == null || session.minecraft.screen != session.root || session.finishingLeave) return false;
        session.captureOwnership();
        if (session.nativeLoadDepth == 0 && session.minecraft.level == null && session.ownedServer != null && !session.ownedServer.isShutdown()) {
            if (session.failure == null && !session.cancelRequested) session.failure = Component.translatable("editor.workbench.world_load_failed");
            session.leave(session.cancelRequested);
            return true;
        }
        return false;
    }

    private void finishOffline(boolean clearFailure) {
        setChild(null);
        receiving = false;
        cancelRequested = false;
        deferred = null;
        state = clearFailure ? State.OFFLINE : State.FAILED;
        if (clearFailure) failure = null;
        if (creation != null) {
            var attempt = creation;
            creation = null;
            ++generation;
            creationReturn = attempt.cancelled;
        }
        changed.run();
    }

    private void loadFailed(RuntimeException error) {
        fail(error, "world_load_failed");
        if (minecraft.level != null || minecraft.getSingleplayerServer() != null) deferred = () -> leave(false);
        else finishOffline(false);
    }

    private void fail(Throwable error, String key) {
        LogUtils.getLogger().warn("Editor world session failed", error);
        failure = Component.translatable("editor.workbench." + key);
        changed.run();
    }

    private void setChild(Screen next) {
        if (child == next) return;
        inputBounds = null;
        inputChild = null;
        renderFailed = false;
        failedRenderChild = null;
        failedRenderOverlay = null;
        if (child != null) child.removed();
        child = next;
        focusedChild = false;
        if (child != null) {
            ownedScreens.put(child, generation);
            child.added();
            child.init(minecraft, Math.max(1, childWidth), Math.max(1, childHeight));
        }
        root.cancelWorldInput();
        changed.run();
    }

    private boolean route(Screen next) {
        // A known child belongs permanently to its original load. A retired confirmation cannot
        // return to or resume a later world selection. A new launch is blocked until native terminal.
        if (next != null && ownedScreens.containsKey(next) && ownedScreens.get(next) != generation) return true;
        if (state == State.LEAVING) {
            if (next instanceof ProgressScreen) setChild(next);
            return true;
        }
        if (next instanceof SessionReturnScreen returned) {
            if (returned.owner != this || returned.ticket != generation) return true;
            if (nativeLoadDepth == 0 && minecraft.level == null) {
                boolean clearFailure = true;
                if (creation != null) {
                    // Vanilla reports directory/copy failures by toast and returns here,
                    // without throwing. Preserve a failed submission in the project draft.
                    if (creation.submitted && !cancelRequested && failure == null) failure = Component.translatable("editor.workbench.world_load_failed");
                    clearFailure = failure == null;
                }
                captureOwnership();
                if (ownedServer != null) leave(clearFailure);
                else finishOffline(clearFailure);
            }
            return true;
        }
        if (next == root) return true;
        if (next == null || next instanceof TitleScreen) {
            if (receiving && minecraft.level != null && minecraft.player != null && minecraft.getConnection() != null) {
                setChild(null);
                state = State.CONNECTED;
                receiving = false;
                changed.run();
            } else if (nativeLoadDepth == 0 && minecraft.level == null) {
                captureOwnership();
                if (ownedServer != null) leave(cancelRequested);
                else finishOffline(cancelRequested);
            } else setChild(null);
            return true;
        }
        if (next instanceof ReceivingLevelScreen) {
            receiving = true;
            state = State.LOADING;
        }
        if (creation != null && creation.screen != null && next instanceof GenericDirtMessageScreen && next.getTitle().equals(Component.translatable("createWorld.preparing"))) creation.submitted = true;
        if (next instanceof CreateWorldScreen screen) configureCreation(screen);
        setChild(next);
        return true;
    }

    public static boolean routeScreen(Screen next) {
        var session = active;
        return session != null && session.minecraft.screen == session.root && session.route(next);
    }

    public static WorkbenchWorldSession nativeLoadStarted() {
        var session = active;
        if (session == null || session.minecraft.screen != session.root) return null;
        session.nativeLoadDepth++;
        return session;
    }

    /** The creation bridge checks again after vanilla's forced render and directory opening. */
    public static boolean nativeCreationAllowed(WorkbenchWorldSession session, String id) {
        if (session == null) return true;
        var attempt = session.creation;
        return attempt != null && attempt.ticket == session.generation && attempt.screen != null && !session.cancelRequested && id.equals(attempt.screen.getUiState().getTargetFolder());
    }

    public static void nativeCreationDiscarded(WorkbenchWorldSession session, String id, Exception error) {
        if (session == null) return;
        var attempt = session.creation;
        if (attempt == null || attempt.ticket != session.generation || attempt.screen == null || !id.equals(attempt.screen.getUiState().getTargetFolder())) return;
        if (error != null) session.fail(error, "world_load_failed");
        session.finishOffline(error == null);
    }

    public static void nativeLoadFinished(WorkbenchWorldSession session) {
        if (session != null) {
            session.captureOwnership();
            session.nativeLoadDepth = Math.max(0, session.nativeLoadDepth - 1);
            session.changed.run();
        }
    }

    public static void nativeLoadFailed(WorkbenchWorldSession session, RuntimeException error) {
        if (session != null) session.loadFailed(error);
    }

    /** Per-attempt return marker survives vanilla asynchronous resource-pack callbacks. */
    private final class SessionReturnScreen extends Screen {

        final WorkbenchWorldSession owner = WorkbenchWorldSession.this;
        final long ticket;

        SessionReturnScreen(long ticket) {
            super(Component.empty());
            this.ticket = ticket;
        }
    }

    public static void receivedSpawn() {
        if (active != null && active.minecraft.screen == active.root && active.child instanceof ReceivingLevelScreen receiving) receiving.loadingPacketsReceived();
    }

    public static boolean renderOverlay(Overlay overlay, GuiGraphics graphics, int x, int y, float partial) {
        var session = active;
        if (session == null || session.minecraft.screen != session.root || session.nativeRendering) return false;
        long before = session.overlayRenderCalls;
        session.root.render(graphics, x, y, partial);
        // Empty, form and grid views have no embedded native surface. LoadingOverlay.render
        // also drives reload completion, so those views must still render the current overlay.
        if (session.overlayRenderCalls == before && session.minecraft.getOverlay() == overlay) {
            session.nativeRendering = true;
            try {
                session.overlayRenderCalls++;
                overlay.render(graphics, x, y, partial);
            } finally {
                session.nativeRendering = false;
            }
        }
        return true;
    }

    void configure(ControlTree.Bounds bounds) {
        if (!Objects.equals(this.bounds, bounds)) {
            inputBounds = null;
            inputChild = null;
        }
        this.bounds = bounds;
        if (bounds == null) return;
        if (childWidth != bounds.width() || childHeight != bounds.height()) {
            childWidth = bounds.width();
            childHeight = bounds.height();
            if (child != null) child.resize(minecraft, Math.max(1, childWidth), Math.max(1, childHeight));
        }
    }

    void render(GuiGraphics outer, ControlTree.Bounds bounds, float density, int mouseX, int mouseY, float partial) {
        configure(bounds);
        if (!hasNative()) return;
        Screen paintedChild = child;
        Overlay paintedOverlay = minecraft.getOverlay();
        long paintedGeneration = generation;
        if (renderFailed && failedRenderChild == paintedChild && failedRenderOverlay == paintedOverlay) return;
        try {
            nativeSurface.render(outer, bounds, density, (graphics) -> {
                nativeRendering = true;
                try {
                    var overlay = minecraft.getOverlay();
                    if (overlay != null) {
                        overlayRenderCalls++;
                        overlay.render(graphics, mouseX - bounds.x(), mouseY - bounds.y(), partial);
                    } else renderBackdrop(graphics, mouseX - bounds.x(), mouseY - bounds.y(), partial);
                } finally {
                    nativeRendering = false;
                }
            });
        } catch (RuntimeException error) {
            inputBounds = null;
            inputChild = null;
            renderFailed = true;
            failedRenderChild = paintedChild;
            failedRenderOverlay = paintedOverlay;
            fail(error, "world_render_failed");
            return;
        }
        renderFailed = false;
        failedRenderChild = null;
        failedRenderOverlay = null;
        if (paintedOverlay == null && child == paintedChild && generation == paintedGeneration && Objects.equals(this.bounds, bounds) && minecraft.getOverlay() == null) {
            inputBounds = bounds;
            inputChild = paintedChild;
            inputGeneration = paintedGeneration;
        } else {
            inputBounds = null;
            inputChild = null;
        }
    }

    void renderBackdrop(GuiGraphics graphics, int x, int y, float partial) {
        if (child != null) child.renderWithTooltip(graphics, x, y, partial);
        else graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), 0xff000000);
    }

    private boolean acceptsNativeInput() {
        return child != null && child == inputChild && inputBounds != null && inputGeneration == generation && minecraft.getOverlay() == null;
    }

    private boolean nativeInput(BooleanSupplier action) {
        boolean creating = creation != null;
        try {
            return action.getAsBoolean();
        } catch (RuntimeException error) {
            if (!creating) throw error;
            loadFailed(error);
            return true;
        }
    }

    boolean click(double x, double y, int button) {
        if (!acceptsNativeInput() || !inputBounds.contains(x, y)) return false;
        focusedChild = true;
        return nativeInput(() -> child.mouseClicked(x - inputBounds.x(), y - inputBounds.y(), button));
    }

    boolean release(double x, double y, int button) {
        return acceptsNativeInput() && focusedChild && nativeInput(() -> child.mouseReleased(x - inputBounds.x(), y - inputBounds.y(), button));
    }

    boolean drag(double x, double y, int button, double dx, double dy) {
        return acceptsNativeInput() && focusedChild && nativeInput(() -> child.mouseDragged(x - inputBounds.x(), y - inputBounds.y(), button, dx, dy));
    }

    boolean scroll(double x, double y, double delta) {
        return acceptsNativeInput() && inputBounds.contains(x, y) && nativeInput(() -> child.mouseScrolled(x - inputBounds.x(), y - inputBounds.y(), delta));
    }

    boolean key(int key, int scan, int modifiers) {
        return acceptsNativeInput() && (creatingWorld() || focusedChild) && nativeInput(() -> child.keyPressed(key, scan, modifiers));
    }

    boolean character(char value, int modifiers) {
        return acceptsNativeInput() && (creatingWorld() || focusedChild) && nativeInput(() -> child.charTyped(value, modifiers));
    }

    /** Global cancellation does not require a prior click or a rendered pointer lease. */
    void escape() {
        Screen current = child;
        NativeEscape.dispatch(active == this && minecraft.screen == root, loading(), minecraft.getOverlay() != null,
                this::requestCancel, current == null ? null : () -> nativeInput(() -> current.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0)));
    }

    /** Keeps cancellation ownership separate from the focus rules for ordinary native input. */
    static final class NativeEscape {

        private NativeEscape() {}

        static void dispatch(boolean activeOwner, boolean loading, boolean overlay, Runnable cancelLoad, BooleanSupplier childEscape) {
            if (!activeOwner) return;
            if (loading) {
                cancelLoad.run();
                return;
            }
            // The child owns refusal, dismissal and replacement. Never retry or force a close.
            if (!overlay && childEscape != null) childEscape.getAsBoolean();
        }
    }

    void clearFocus() {
        focusedChild = false;
        if (child != null) child.setFocused(null);
    }

    boolean mayReturn() {
        return !loading() && child == null && minecraft.getOverlay() == null;
    }

    void detach() {
        inputBounds = null;
        inputChild = null;
        observedOverlay = null;
        if (creation != null || creationReturn != null) {
            creation = null;
            creationReturn = null;
            ++generation;
        }
        if (active == this) active = null;
        clearFocus();
        nativeSurface.close();
    }

    @Override
    public void close() {
        detach();
    }
}
