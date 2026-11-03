package net.ptcrys.fpsmatch.editor.core.nativeitemprobe;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.FPSMatch;

/** One synchronous attempt on END CLIENT_TICK, with no world, connection or reload active. */
@Mod.EventBusSubscriber(modid = "blockoffensive", value = Dist.CLIENT)
public final class ClientNativeItemProbe {
    private static boolean attempted;

    private ClientNativeItemProbe() { }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("q48.probe.enabled")) throw new IllegalStateException("Explicit q48.probe.enabled=true required");
        if (event.phase != TickEvent.Phase.END || attempted) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread() || minecraft.level != null || minecraft.getConnection() != null
                || minecraft.getOverlay() != null || !(minecraft.screen instanceof TitleScreen)) return;
        attempted = true;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", "REAL_CLIENT_REGISTERED_NATIVE_ITEM_MATERIALIZATION");
        result.put("owner", true);
        result.put("ownerThread", Thread.currentThread().getName());
        result.put("worldEntered", false);
        result.put("syntheticResourceContext", true);
        result.put("resourceProducers", 0);
        result.put("capabilityIsolation", "not_asserted");
        long started = System.nanoTime();
        boolean passed = false;
        try {
            if (!Boolean.getBoolean("q48.probe.enabled")) throw new IllegalStateException("Q48 disabled");
            Map<String, String> versions = new LinkedHashMap<>();
            versions.put("minecraft", SharedConstants.getCurrentVersion().getName());
            ModList.get().getMods().stream().sorted(java.util.Comparator.comparing(info -> info.getModId()))
                    .forEach(info -> versions.put(info.getModId(), info.getVersion().toString()));
            result.put("versions", versions);
            FPSMatch fpsmatch = (FPSMatch) ModList.get().getModContainerById(FPSMatch.MODID).orElseThrow().getMod();
            ExtensionRegistry registry = fpsmatch.editorRegistry();
            if (!registry.metadata().problems().isEmpty()) throw new IllegalStateException("Registry diagnostics present");
            result.put("contract", NativeItemContractChecks.run(registry));
            result.put("fixtures", NativeItemFixtureDiagnostic.run(registry));
            if (!minecraft.isSameThread() || minecraft.level != null || minecraft.getConnection() != null
                    || minecraft.getOverlay() != null || !(minecraft.screen instanceof TitleScreen)) {
                throw new IllegalStateException("Title context changed during synchronous diagnostic");
            }
            result.put("failures", List.of());
            passed = true;
        } catch (Exception | AssertionError failure) {
            result.put("failures", List.of(failure.getClass().getName() + ": " + failure.getMessage()));
            failure.printStackTrace(System.err);
        }
        result.put("phase", passed ? "PASS" : "FAILED");
        result.put("elapsedNanos", System.nanoTime() - started);
        result.put("levelNull", minecraft.level == null);
        result.put("connectionNull", minecraft.getConnection() == null);
        try {
            Path game = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
            Files.writeString(game.getParent().resolve("q48-result.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException writeFailure) {
            passed = false;
            System.out.println("Q48_NATIVE_ITEM_RESULT_WRITE_ERROR kind=" + writeFailure.getClass().getSimpleName());
        }
        System.out.println(passed ? "Q48_NATIVE_ITEM_PASS" : "Q48_NATIVE_ITEM_FAILED");
    }
}
