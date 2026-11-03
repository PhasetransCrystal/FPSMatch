package net.ptcrys.fpsmatch.editor.core.pinnedprobe;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.PinnedSnapshotPack;

/** All transitions and publication run on END CLIENT_TICK's Minecraft owner thread. */
@Mod.EventBusSubscriber(modid = "blockoffensive", value = Dist.CLIENT)
public final class ClientPinnedPackProbe {
    private enum Phase { WAIT_TITLE, MOUNTING, VERIFYING, RESTORING, PASS, FAILED }
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(180);
    private static final ClientPinnedPackProbe INSTANCE = new ClientPinnedPackProbe();

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean scanTaskCompleted = new AtomicBoolean();
    private final AtomicLong factoryOpens = new AtomicLong();
    private final AtomicLong factoryNanos = new AtomicLong();
    private final PinnedPackDiagnostic.Progress progress = new PinnedPackDiagnostic.Progress();
    private final List<String> failures = new ArrayList<>();
    private final Map<String, String> versions = new LinkedHashMap<>();
    private Phase phase = Phase.WAIT_TITLE;
    private long epoch;
    private long phaseStarted;
    private long mountedFactoryGeneration;
    private volatile boolean active;
    private volatile long factoryDeadline;
    private boolean attempted;
    private boolean finished;
    private boolean contextLost;
    private boolean restoreAttempted;
    private boolean restoreSucceeded;
    private boolean restoreSkipped;
    private boolean supplierRejected;
    private boolean physicalStreamRejected;
    private boolean diagnosticCancelAfterFirstAsset;
    private String ownerThread = "";
    private List<String> selectedBefore = List.of();
    private List<String> mountedSelection = List.of();
    private PinnedPackDiagnostic.Inputs inputs;
    private PinnedSnapshotPack mountedPack;
    private ResourceManager capturedManager;
    private IoSupplier<InputStream> oldSupplier;
    private InputStream oldPhysicalStream;
    private PackRepository repository;

    private ClientPinnedPackProbe() { }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("q47.probe.enabled")) throw new IllegalStateException("Explicit q47.probe.enabled=true required");
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) return;
        INSTANCE.tick(minecraft);
    }

    private void tick(Minecraft minecraft) {
        if (finished) return;
        if (!attempted) {
            if (!(minecraft.screen instanceof TitleScreen) || !emptyContext(minecraft)
                    || minecraft.getOverlay() != null) return;
            attempted = true;
            ownerThread = Thread.currentThread().getName();
            try {
                mount(minecraft);
            } catch (Exception failure) {
                fail(minecraft, "mount", failure);
            }
            return;
        }
        if (!emptyContext(minecraft)) {
            contextLost = true;
            fail(minecraft, "world_or_connection_changed", null);
            return;
        }
        if (expired()) {
            fail(minecraft, "timeout_" + phase, null);
            return;
        }
        if (phase == Phase.VERIFYING) {
            if (diagnosticCancelAfterFirstAsset && progress.verifiedAssets >= 1) {
                fail(minecraft, "diagnostic_cancel_after_first_asset", null);
                return;
            }
            try {
                requireMounted(minecraft);
            } catch (Exception failure) {
                fail(minecraft, "mounted_generation_changed", failure);
            }
        }
    }

    private void mount(Minecraft minecraft) throws IOException {
        requireOwner(minecraft);
        if (!Boolean.getBoolean("q47.probe.enabled")) throw new IOException("Q47 probe disabled");
        String cancelAfterFirstAsset = System.getProperty("q47.probe.cancelAfterFirstAsset");
        if (cancelAfterFirstAsset != null && !cancelAfterFirstAsset.equals("true")) {
            throw new IOException("q47.probe.cancelAfterFirstAsset must be absent or exactly true");
        }
        diagnosticCancelAfterFirstAsset = cancelAfterFirstAsset != null;
        versions.put("minecraft", SharedConstants.getCurrentVersion().getName());
        ModList.get().getMods().stream().sorted(java.util.Comparator.comparing(info -> info.getModId()))
                .forEach(info -> versions.put(info.getModId(), info.getVersion().toString()));
        inputs = PinnedPackDiagnostic.inputs();
        repository = minecraft.getResourcePackRepository();
        selectedBefore = selected();
        if (selectedBefore.contains(PinnedPackDiagnostic.PACK_ID)) throw new IOException("Diagnostic pack already selected");
        enter(Phase.MOUNTING);
        active = true;
        factoryDeadline = phaseStarted + TIMEOUT_NANOS;
        repository.addPackFinder(consumer -> {
            if (!active) return;
            Pack pack = Pack.readMetaAndCreate(PinnedPackDiagnostic.PACK_ID,
                    Component.translatable("fpsmatch.editor.snapshot_pack"), false, this::openPack,
                    PackType.CLIENT_RESOURCES, Pack.Position.TOP, PackSource.DEFAULT);
            if (pack == null) throw new IllegalStateException("Q47 actual pack metadata read failed");
            consumer.accept(pack);
        });
        repository.reload();
        List<String> selection = new ArrayList<>(selectedBefore);
        selection.add(PinnedPackDiagnostic.PACK_ID);
        mountedSelection = List.copyOf(selection);
        repository.setSelected(mountedSelection);
        if (!selected().equals(mountedSelection)) throw new IOException("Diagnostic selected order differs");
        if (expired()) throw new IOException("Synchronous mount exceeded 180 seconds");
        long token = epoch;
        CompletableFuture<Void> reload = minecraft.reloadResourcePacks();
        // The real host opens selected packs synchronously before returning this future.
        // Freeze that invocation's generation now, rather than accepting a later reload's packs.
        mountedFactoryGeneration = factoryOpens.get();
        reload.whenComplete((ignored, failure) -> minecraft.execute(() -> {
            if (!accept(minecraft, Phase.MOUNTING, token)) return;
            if (failure != null) {
                fail(minecraft, "mount_reload", failure);
                return;
            }
            try {
                beginVerification(minecraft);
            } catch (Exception verificationFailure) {
                fail(minecraft, "begin_verification", verificationFailure);
            }
        }));
    }

    private PackResources openPack(String id) {
        if (!PinnedPackDiagnostic.PACK_ID.equals(id)) throw new IllegalArgumentException("Unexpected Q47 pack id");
        factoryOpens.incrementAndGet();
        long started = System.nanoTime();
        try {
            // Deliberately synchronous full archive authentication: this is diagnostic cost, not a product installer.
            return PinnedSnapshotPack.open(id, inputs.sources(),
                    () -> cancelled.get() || !active || System.nanoTime() - factoryDeadline >= 0);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } finally {
            factoryNanos.addAndGet(System.nanoTime() - started);
        }
    }

    private void beginVerification(Minecraft minecraft) throws IOException {
        if (!selected().equals(mountedSelection)) throw new IOException("Mount selected order differs");
        capturedManager = minecraft.getResourceManager();
        List<PackResources> matching;
        try (var packs = capturedManager.listPacks()) {
            matching = packs.filter(pack -> PinnedPackDiagnostic.PACK_ID.equals(pack.packId())).toList();
        }
        if (matching.size() != 1 || !(matching.get(0) instanceof PinnedSnapshotPack pinned)) {
            throw new IOException("Actual manager must contain exactly one Q46 pack");
        }
        mountedPack = pinned;
        requireMounted(minecraft);
        PinnedPackDiagnostic.Expected retained = inputs.assets().stream().filter(entry -> entry.size() > 1)
                .findFirst().orElseThrow(() -> new IOException("No nonempty physical entry to retain"));
        oldSupplier = mountedPack.getResource(PackType.CLIENT_RESOURCES, retained.location());
        if (oldSupplier == null) throw new IOException("Retained physical supplier missing");
        oldPhysicalStream = oldSupplier.get();
        if (oldPhysicalStream.read() == -1) throw new IOException("Retained physical stream unexpectedly empty");
        enter(Phase.VERIFYING);
        long token = epoch;
        // A single task on the existing executor, with no private executor or per-resource futures.
        CompletableFuture.runAsync(() -> {
            try {
                PinnedPackDiagnostic.scan(capturedManager, inputs.assets(), cancelled, factoryOpens,
                        mountedFactoryGeneration, progress);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            } finally {
                scanTaskCompleted.set(true);
                System.out.println("Q47_SCAN_TASK_COMPLETED cancelled=" + cancelled.get());
            }
        }, Util.backgroundExecutor()).whenComplete((ignored, failure) -> minecraft.execute(() -> {
            if (!accept(minecraft, Phase.VERIFYING, token)) return;
            if (diagnosticCancelAfterFirstAsset && progress.verifiedAssets >= 1) {
                fail(minecraft, "diagnostic_cancel_after_first_asset", null);
                return;
            }
            if (failure != null) {
                fail(minecraft, "scan", failure);
                return;
            }
            try {
                requireMounted(minecraft);
                if (progress.verifiedAssets != PinnedPackDiagnostic.ASSET_COUNT) {
                    throw new IOException("Incomplete expected resource scan");
                }
                restore(minecraft);
            } catch (Exception restoreFailure) {
                fail(minecraft, "restore_start", restoreFailure);
            }
        }));
    }

    private void requireMounted(Minecraft minecraft) throws IOException {
        requireOwner(minecraft);
        if (!emptyContext(minecraft) || contextLost || minecraft.getResourceManager() != capturedManager
                || factoryOpens.get() != mountedFactoryGeneration || !selected().equals(mountedSelection)) {
            throw new IOException("Captured manager, context, selection or factory generation changed");
        }
        // ReloadableResourceManager is a mutable wrapper: verify the actual mounted object identity.
        List<PackResources> matches;
        try (var packs = capturedManager.listPacks()) {
            matches = packs.filter(pack -> PinnedPackDiagnostic.PACK_ID.equals(pack.packId())).toList();
        }
        if (matches.size() != 1 || matches.get(0) != mountedPack) throw new IOException("Actual mounted pack generation changed");
    }

    private void restore(Minecraft minecraft) throws IOException {
        requireOwner(minecraft);
        if (contextLost || !emptyContext(minecraft)) {
            contextLost = true;
            restoreSkipped = true;
            failures.add("restore_skipped_context_changed");
            finish(minecraft);
            return;
        }
        restoreAttempted = true;
        active = false;
        enter(Phase.RESTORING);
        repository.reload();
        repository.setSelected(selectedBefore);
        if (!selected().equals(selectedBefore)) throw new IOException("Original selected order could not be restored");
        long token = epoch;
        minecraft.reloadResourcePacks().whenComplete((ignored, failure) -> minecraft.execute(() -> {
            if (!accept(minecraft, Phase.RESTORING, token)) return;
            try {
                if (failure != null) throw new IOException("Restore reload failed", failure);
                if (!selected().equals(selectedBefore)) throw new IOException("Restored selected order differs");
                try (var packs = minecraft.getResourceManager().listPacks()) {
                    if (packs.anyMatch(pack -> PinnedPackDiagnostic.PACK_ID.equals(pack.packId()))) {
                        throw new IOException("Diagnostic pack remains in actual manager");
                    }
                }
                restoreSucceeded = true;
                if (oldSupplier != null) {
                    try {
                        InputStream unexpected = oldSupplier.get();
                        try {
                            unexpected.close();
                        } catch (IOException closeFailure) {
                            failures.add(describe("unexpected_supplier_stream_close", closeFailure));
                        }
                        throw new IllegalStateException("Old physical supplier opened after restore");
                    } catch (IOException expected) {
                        supplierRejected = true;
                    }
                }
                if (oldPhysicalStream != null) {
                    try {
                        oldPhysicalStream.read();
                        throw new IllegalStateException("Old physical stream remained readable after restore");
                    } catch (IOException expected) {
                        physicalStreamRejected = true;
                    }
                }
                if (failures.isEmpty() && (!supplierRejected || !physicalStreamRejected)) {
                    failures.add("old_physical_entries_not_proven_invalid");
                }
            } catch (Exception restoreFailure) {
                failures.add(describe("restore_verification", restoreFailure));
            } finally {
                finish(minecraft);
            }
        }));
    }

    private boolean accept(Minecraft minecraft, Phase expectedPhase, long token) {
        requireOwner(minecraft);
        if (finished || phase != expectedPhase || epoch != token) return false;
        if (!emptyContext(minecraft)) {
            contextLost = true;
            fail(minecraft, "world_or_connection_changed", null);
            return false;
        }
        if (expired()) {
            fail(minecraft, "timeout_" + phase, null);
            return false;
        }
        return true;
    }

    private void fail(Minecraft minecraft, String reason, Throwable failure) {
        requireOwner(minecraft);
        if (finished) return;
        Phase failedPhase = phase;
        failures.add(describe(reason, failure));
        cancelled.set(true);
        active = false;
        epoch++; // Late futures cannot advance this attempt or publish success.
        if (!emptyContext(minecraft)) contextLost = true;
        if (contextLost) {
            restoreSkipped = true;
            failures.add("restore_skipped_context_changed");
            finish(minecraft);
        } else if (repository != null && failedPhase != Phase.RESTORING && !restoreAttempted) {
            try {
                restore(minecraft);
            } catch (Exception restoreFailure) {
                failures.add(describe("restore_start", restoreFailure));
                finish(minecraft);
            }
        } else {
            finish(minecraft);
        }
    }

    private void enter(Phase next) {
        phase = next;
        phaseStarted = System.nanoTime();
        epoch++;
        System.out.println("Q47_PINNED_CLIENT_RELOAD_PHASE phase=" + next + " epoch=" + epoch);
    }

    private boolean expired() {
        return phase != Phase.WAIT_TITLE && System.nanoTime() - phaseStarted >= TIMEOUT_NANOS;
    }

    private List<String> selected() {
        return repository.getSelectedPacks().stream().map(Pack::getId).toList();
    }

    private void finish(Minecraft minecraft) {
        requireOwner(minecraft);
        if (finished) return;
        finished = true;
        active = false;
        cancelled.set(true);
        epoch++;
        try {
            if (oldPhysicalStream != null) oldPhysicalStream.close();
        } catch (IOException closeFailure) {
            failures.add(describe("retained_stream_close", closeFailure));
        } finally {
            oldPhysicalStream = null;
        }
        phase = failures.isEmpty() && restoreSucceeded && supplierRejected && physicalStreamRejected
                && progress.verifiedAssets == PinnedPackDiagnostic.ASSET_COUNT ? Phase.PASS : Phase.FAILED;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", phase.name());
        result.put("owner", minecraft.isSameThread());
        result.put("ownerThread", ownerThread);
        result.put("levelNull", minecraft.level == null);
        result.put("connectionNull", minecraft.getConnection() == null);
        result.put("contextLost", contextLost);
        result.put("versions", versions);
        result.put("packId", PinnedPackDiagnostic.PACK_ID);
        result.put("sourceOrder", List.of("default", "mcs", "gz"));
        result.put("sourceSha256", PinnedPackDiagnostic.SOURCE_SHA);
        result.put("selectedBefore", selectedBefore);
        result.put("selectedAfter", repository == null ? List.of() : selected());
        result.put("expectedAssets", PinnedPackDiagnostic.ASSET_COUNT);
        result.put("diagnosticCancelAfterFirstAsset", diagnosticCancelAfterFirstAsset);
        result.put("scanTaskCompleted", scanTaskCompleted.get());
        result.put("verifiedAssets", progress.verifiedAssets);
        result.put("listReadEntries", progress.listReadEntries);
        result.put("getReadEntries", progress.getReadEntries);
        result.put("readEntries", progress.listReadEntries + progress.getReadEntries);
        result.put("bytesRead", progress.bytesRead);
        result.put("maximumEntry", progress.maximumEntry);
        result.put("maximumEntryBytes", progress.maximumEntryBytes);
        result.put("oldSupplierRejected", supplierRejected);
        result.put("oldPhysicalStreamRejected", physicalStreamRejected);
        result.put("restoreAttempted", restoreAttempted);
        result.put("restoreSucceeded", restoreSucceeded);
        result.put("restoreSkipped", restoreSkipped);
        result.put("factoryOpens", factoryOpens.get());
        result.put("factoryAuthenticationNanos", factoryNanos.get());
        result.put("factoryAuthenticationSynchronous", true);
        result.put("failures", List.copyOf(failures));
        try {
            Path game = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
            Files.writeString(game.getParent().resolve("q47-result.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException writeFailure) {
            phase = Phase.FAILED;
            System.out.println("Q47_PINNED_CLIENT_RELOAD_RESULT_WRITE_ERROR kind=" + writeFailure.getClass().getSimpleName());
        }
        System.out.println(phase == Phase.PASS ? "Q47_PINNED_CLIENT_RELOAD_PASS" : "Q47_PINNED_CLIENT_RELOAD_FAILED");
    }

    private static String describe(String reason, Throwable failure) {
        if (failure == null) return reason;
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return reason + ": " + failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
    }

    private static boolean emptyContext(Minecraft minecraft) {
        return minecraft.level == null && minecraft.getConnection() == null;
    }

    private static void requireOwner(Minecraft minecraft) {
        if (!minecraft.isSameThread()) throw new IllegalStateException("Q47 transition outside Minecraft owner thread");
    }
}
