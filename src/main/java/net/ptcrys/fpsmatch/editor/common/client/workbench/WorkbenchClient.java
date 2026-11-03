package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import net.ptcrys.fpsmatch.editor.common.ui.persistence.ProjectWorldStore;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.ProjectWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.ProjectWorldWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.RuntimeInstallationWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.RuntimeTestWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.WorldTargetWorkbench;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProviders;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.Executors;

/** The two original mods own registration; this is only a client event subscriber and UI lifetime. */
@Mod.EventBusSubscriber(modid = "fpsmatch", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class WorkbenchClient {

    private static ProjectWorkbench model;
    private static ProjectWorldWorkbench projectWorld;
    private static WorkbenchScreen screen;
    private static WorldTargetWorkbench worldTarget;
    private static RuntimeInstallationWorkbench installations;
    private static RuntimeTestWorkbench tests;
    private static int worldTicks;
    private static boolean hadProject;

    private WorkbenchClient() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen) && !(event.getScreen() instanceof TitleScreen)) return;
        event.addListener(Button.builder(Component.translatable("editor.workbench.open"), button -> {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> open(minecraft));
        }).bounds(Math.max(4, event.getScreen().width - 114), 6, 108, 20).build());
    }

    /** Open the existing project workspace; runtime rooms never select or mutate an authoring source implicitly. */
    public static void openWorkbench() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> open(minecraft));
    }

    private static void open(Minecraft minecraft) {
        if (model == null) {
            FPSMatch mod = (FPSMatch) ModList.get().getModContainerById("fpsmatch").orElseThrow().getMod();
            var worker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "fpsmatch-editor-io");
                thread.setDaemon(true);
                return thread;
            });
            model = new ProjectWorkbench(mod.editorRegistry(), worker, minecraft::execute,
                    (diagnostic, failure) -> com.mojang.logging.LogUtils.getLogger().warn(
                            "Editor diagnostic #{} [{}] {}", diagnostic.eventId(), diagnostic.operation(), diagnostic.exceptionType(), failure));
            var identities = java.io.File.separatorChar == 92 ? new WindowsFileIdentityProvider() : FileIdentityProviders.jdk();
            projectWorld = new ProjectWorldWorkbench(model, new ProjectWorldStore(minecraft.gameDirectory.toPath().toAbsolutePath().resolve("config/fpsmatch-editor-worlds"), identities), worker, minecraft::execute);
            worldTarget = new WorldTargetWorkbench(new MinecraftWorkbenchWorldTarget(minecraft, worker, mod.editorRegistry(), projectWorld), minecraft::execute);
            installations = new RuntimeInstallationWorkbench(model, worldTarget::installationTarget);
            tests = new RuntimeTestWorkbench(worldTarget::installationTarget);
            var layoutWorker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "fpsmatch-editor-layout-io");
                thread.setDaemon(true);
                return thread;
            });
            screen = new WorkbenchScreen(model, worldTarget, installations, tests, projectWorld, layoutWorker);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    screen.flushLayoutsOnShutdown().get(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception failure) {
                    com.mojang.logging.LogUtils.getLogger().warn("Editor layout shutdown flush was not confirmed", failure);
                } finally {
                    layoutWorker.shutdown();
                }
            }, "fpsmatch-editor-layout-shutdown"));
        }
        minecraft.setScreen(screen);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || worldTarget == null) return;
        projectWorld.tick();
        screen.tickLayouts();
        if (hadProject && !model.hasProject()) worldTarget.disconnect();
        hadProject = model.hasProject();
        worldTarget.tick(++worldTicks % 20 == 0);
        installations.tick();
        tests.tick();
    }
}
