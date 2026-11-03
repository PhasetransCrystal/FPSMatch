package net.ptcrys.fpsmatch.editor.common.backend.windows;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentities;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A real reparse-point check that does not require symbolic-link creation privilege. */
@EnabledOnOs(OS.WINDOWS)
class WindowsJunctionTest {
    @TempDir Path temporary;

    @Test
    void rejectsJunctionAsNodeWorldRootAndAncestorWithoutWritingItsTarget() throws Exception {
        Path target = Files.createDirectory(temporary.resolve("target"));
        Files.writeString(target.resolve("keep"), "original");
        Files.createDirectory(target.resolve("child"));
        Path link = temporary.resolve("junction");
        // Only the generated temporary paths are given to the Windows junction command.
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                link.toString(), target.toString()).redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Junction helper did not terminate");
            throw new AssertionError("Junction helper timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        try {
            assumeTrue(process.exitValue() == 0, "Junction creation unavailable: " + output);
            WindowsFileIdentityProvider provider = new WindowsFileIdentityProvider();
            IOException nodeFailure = assertThrows(IOException.class, () -> provider.identity(link));
            assertTrue(nodeFailure.getMessage().contains("Reparse point"), nodeFailure.getMessage());
            assertThrows(WorldIdentityException.class, () -> WorldIdentities.read(link, provider, () -> false));
            assertThrows(WorldIdentityException.class, () -> WorldIdentities.initialize(link, provider, () -> false));
            assertThrows(WorldIdentityException.class,
                    () -> WorldIdentities.initialize(link.resolve("child"), provider, () -> false));
            assertEquals("original", Files.readString(target.resolve("keep")));
            assertFalse(Files.exists(target.resolve(".editor-platform")));
            assertFalse(Files.exists(target.resolve("child/.editor-platform")));
            System.out.println("WIN32_JUNCTION_REJECTED node/root/ancestor; target unchanged");
        } finally {
            // Delete the junction node only. Never recurse through its target.
            Files.deleteIfExists(link);
        }
    }
}
