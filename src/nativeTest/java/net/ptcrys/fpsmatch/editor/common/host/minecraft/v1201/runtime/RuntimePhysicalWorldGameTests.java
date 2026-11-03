package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.RuntimeMapLeaseException;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProviders;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentities;

/** Read-only server checks; only private temporary identity copies are written. */
@GameTestHolder("fpsmatch")
@PrefixGameTestTemplate(false)
public final class RuntimePhysicalWorldGameTests {
    private RuntimePhysicalWorldGameTests() {}

    @GameTest(template = "native_item_empty", timeoutTicks = 100, required = true, attempts = 1)
    public static void physicalWorldRequiresExactPathAndNode(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        verifyPhysicalWorld(server);
        helper.succeed();
    }

    public static void verifyPhysicalWorld(MinecraftServer server) throws Exception {
        MinecraftRuntimeInstallations controller = controller(server);
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        FileIdentityProvider identities = identities();
        var node = identities.identity(root);
        var status = controller.status();
        var records = controller.records();
        controller.requirePhysicalWorld(root, node);
        controller.requirePhysicalWorld(root.resolve("unused").resolve(".."), node);
        Path unrelated = Files.createTempDirectory("runtime-world-test-");
        try {
            var differentNode = identities.identity(unrelated);
            require(!node.equals(differentNode), "Temporary directory must have a different physical identity");
            refused(RuntimeMapLeaseException.Reason.INVALID_INPUT, () -> controller.requirePhysicalWorld(root, differentNode));
            refused(RuntimeMapLeaseException.Reason.INVALID_INPUT, () -> controller.requirePhysicalWorld(unrelated, node));
            refused(RuntimeMapLeaseException.Reason.INVALID_INPUT, () -> controller.requirePhysicalWorld(root, null));
            refused(RuntimeMapLeaseException.Reason.INVALID_INPUT, () -> controller.requirePhysicalWorld(Path.of("."), node));
            CompletableFuture.runAsync(() -> refused(RuntimeMapLeaseException.Reason.WRONG_THREAD,
                    () -> controller.requirePhysicalWorld(null, null))).join();
            controller.requirePhysicalWorld(root, node);
            require(controller.status() == status && controller.records().equals(records), "Rejected target changed installation ownership");
        } finally { Files.delete(unrelated); }
    }

    /** Optional owner-thread host regression after explicit world registration; never registers the live save. */
    public static void registeredWorldCopyCannotInheritAuthority(MinecraftServer server) throws IOException {
        MinecraftRuntimeInstallations controller = controller(server);
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        FileIdentityProvider identities = identities();
        var original = WorldIdentities.read(root, identities, () -> false);
        var originalNode = identities.identity(root);
        Path copy = Files.createTempDirectory("runtime-world-copy-test-");
        Path editor = copy.resolve(".editor-platform");
        Path marker = editor.resolve("world.identity");
        try {
            Files.createDirectory(editor);
            Files.copy(root.resolve(".editor-platform/world.identity"), marker);
            var duplicate = WorldIdentities.read(copy, identities, () -> false);
            require(original.worldId().equals(duplicate.worldId()), "Copy must retain the same world UUID");
            var duplicateNode = identities.identity(copy);
            require(!originalNode.equals(duplicateNode), "Copy must have a different physical directory");
            refused(RuntimeMapLeaseException.Reason.INVALID_INPUT, () -> controller.requirePhysicalWorld(copy, duplicateNode));
            refused(RuntimeMapLeaseException.Reason.INVALID_INPUT, () -> controller.requirePhysicalWorld(root, duplicateNode));
            controller.requirePhysicalWorld(root, originalNode);
            WorldIdentities.verify(original, () -> false);
        } finally {
            Files.deleteIfExists(marker);
            Files.deleteIfExists(editor);
            Files.delete(copy);
        }
    }

    private static MinecraftRuntimeInstallations controller(MinecraftServer server) {
        FPSMCore core = FPSMCore.getInstance();
        require(core != null && core.isActive() && core.getServer() == server, "Exact active core required");
        return core.runtimeInstallations();
    }
    private static FileIdentityProvider identities() {
        return java.io.File.separatorChar == '\\'
                ? new WindowsFileIdentityProvider() : FileIdentityProviders.jdk();
    }
    private static void refused(RuntimeMapLeaseException.Reason reason, Runnable action) {
        try { action.run(); }
        catch (RuntimeMapLeaseException failure) {
            require(failure.problem().code().path().equals("runtime_map/" + reason.name().toLowerCase(java.util.Locale.ROOT)),
                    "Unexpected refusal: " + failure);
            return;
        }
        throw new AssertionError("Expected refusal: " + reason);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
